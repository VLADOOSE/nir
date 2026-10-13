package com.vladoose.nir.service;

import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.MailCursorKey;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import com.vladoose.nir.util.InfrastructureFailure;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Приём писем клиентов ящика info@ (спека 2026-10-13-info-mail-leads §2.1): только чтение; сначала «Входящие», потом
 * «Отправленные» — порядок значим: обращение заводит письмо клиента, наш ответ после него берёт его в работу. У каждой
 * папки свой курсор, сбои — как у zakup@ (MailFolderPass); остановка в одной папке останавливает проход.
 * НЕ @Transactional: запись — по письму в ClientMailWriter. Рынок ставит вызывающий (ClientMailScheduler / тест).
 */
@Service
public class ClientMailReceiveService {

    private static final Logger log = LoggerFactory.getLogger(ClientMailReceiveService.class);
    static final int MAX_PER_PASS = 100;

    /** Итог прохода: счётчики писем и причина остановки (null — проход дошёл до конца). */
    public record Result(boolean enabled, int conversations, int outgoing, int inbound, int skipped, int duplicates,
                         int broken, int createdLeads, String stopped) {
        static Result disabled() { return new Result(false, 0, 0, 0, 0, 0, 0, 0, null); }
    }

    private final ClientMailSettings settings;
    private final ClientMailbox mailbox;
    private final ClientMailWriter writer;
    private final MailCursorRepository cursorRepository;
    private final Clock clock;
    /** «Отправленных» нет — предупреждение один раз, а не каждую минуту. Поток приёма один — volatile не нужен. */
    private boolean sentMissingWarned;

    @Autowired
    public ClientMailReceiveService(ClientMailSettings settings, ClientMailbox mailbox, ClientMailWriter writer,
                                    MailCursorRepository cursorRepository) {
        this(settings, mailbox, writer, cursorRepository, Clock.systemUTC());
    }

    ClientMailReceiveService(ClientMailSettings settings, ClientMailbox mailbox, ClientMailWriter writer,
                             MailCursorRepository cursorRepository, Clock clock) {
        this.settings = settings;
        this.mailbox = mailbox;
        this.writer = writer;
        this.cursorRepository = cursorRepository;
        this.clock = clock;
    }

    public Result poll() {
        if (!settings.enabled()) return Result.disabled();
        Tally tally = new Tally();
        String stopped = null;
        for (MailFolder folder : List.of(MailFolder.INBOX, MailFolder.SENT)) {
            stopped = pass(folder, tally);
            if (stopped != null) break;
        }
        Result r = tally.result(stopped);
        if (stopped != null) {
            log.warn("Приём писем клиентов ({}): {} — проход остановлен, повтор следующим", settings.username(), stopped);
        } else if (r.conversations() + r.outgoing() + r.inbound() + r.broken() > 0) {
            log.info("Приём писем клиентов ({}): {}", settings.username(), summary(r));
        }
        return r;
    }

    /** null — папка пройдена (или «Отправленных» нет); иначе — почему проход остановлен. */
    private String pass(MailFolder folder, Tally tally) {
        String where = settings.username() + " «" + folder.label() + "»: ";
        try (MailboxSession s = mailbox.open(folder)) {
            return MailFolderPass.run(s, target(folder, tally), clock.instant().minus(Duration.ofDays(settings.sinceDays())),
                    MAX_PER_PASS, clock, log, where, false);
        } catch (FolderNotFoundException e) {
            if (folder != MailFolder.SENT) return "папка «" + folder.label() + "» не найдена";
            if (!sentMissingWarned) {
                sentMissingWarned = true;
                log.warn("{}папка не найдена — наши ответы клиентам не читаются и обращения «В работу» сами не переводят", where);
            }
            return null;
        } catch (Exception e) {
            if (InfrastructureFailure.test(e)) return MailFolderPass.dbDown(e);
            // текст почтового сервера — про ящик, его можно; у чужих исключений — только класс: в них бывают адреса и тексты писем
            String cls = e.getClass().getSimpleName();
            if (e instanceof MessagingException) {
                String text = e.getMessage() != null ? e.getMessage() : cls;
                log.warn("{}ошибка почты: {}", where, text);
                return "ошибка почты: " + text;
            }
            log.warn("{}внутренняя ошибка: {}", where, cls);
            return "внутренняя ошибка (" + cls + ")";
        }
    }

    private MailFolderPass.Target<ClientMailWriter.Result> target(MailFolder folder, Tally tally) {
        MailCursorKey key = new MailCursorKey(settings.username(), folder.cursorKey());
        return new MailFolderPass.Target<>() {
            @Override public Optional<MailCursor> cursor() { return cursorRepository.findById(key); }
            @Override public ClientMailWriter.Result write(ParsedMail m, long uidValidity) { return writer.write(m, folder, uidValidity); }
            @Override public void writeBroken(BrokenMail b, long uidValidity) { writer.writeBroken(b, folder, uidValidity); }
            @Override public void moveCursorTo(long uidValidity, long uid) { writer.moveCursorTo(folder, uidValidity, uid); }
            @Override public void written(ClientMailWriter.Result r) { tally.add(r); }
            @Override public void broken() { tally.broken++; }
        };
    }

    static String summary(Result r) {
        List<String> parts = new ArrayList<>();
        if (r.conversations() > 0) parts.add("в переписки — " + r.conversations());
        if (r.createdLeads() > 0) parts.add("новых обращений — " + r.createdLeads());
        if (r.outgoing() > 0) parts.add("наших ответов — " + r.outgoing());
        if (r.inbound() > 0) parts.add("во «Входящие» — " + r.inbound());
        if (r.broken() > 0) parts.add("не разобрано — " + r.broken());
        return String.join(", ", parts);
    }

    private static final class Tally {
        int conversations, outgoing, inbound, skipped, duplicates, broken, createdLeads;

        void add(ClientMailWriter.Result r) {
            switch (r.kind()) {
                case CONVERSATION -> conversations++;
                case OUTGOING -> outgoing++;
                case INBOUND -> inbound++;
                case SKIPPED -> skipped++;
                case DUPLICATE -> duplicates++;
            }
            if (r.createdLeadId() != null) createdLeads++;
        }

        Result result(String stopped) {
            return new Result(true, conversations, outgoing, inbound, skipped, duplicates, broken, createdLeads, stopped);
        }
    }
}
