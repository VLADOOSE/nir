package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.MailCursorKey;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import com.vladoose.nir.util.InfrastructureFailure;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Приём почты ящика АИС (спека 2026-10-05-zakup-mail-telegram §3): ящик только на чтение, курсор по UID, разбор ВНЕ
 * транзакции, запись — по письму в {@link MailIngestWriter}, после писем — уведомления в Telegram.
 * НЕ @Transactional: транзакция на всё время IMAP держала бы соединение с базой. Рынок ставит вызывающий
 * (MailPollScheduler/тест): MarketContext.set(рынок ящика) до вызова.
 */
@Service
public class MailReceiveService {

    private static final Logger log = LoggerFactory.getLogger(MailReceiveService.class);
    static final int MAX_PER_PASS = 100;

    private final MailboxConnector connector;
    private final MailIngestWriter writer;
    private final MailCursorRepository cursorRepository;
    private final MailTelegramNotifier notifier;
    private final boolean enabled;
    private final Market mailboxMarket;
    private final long sinceMinutes;
    private final Clock clock;

    @Autowired
    public MailReceiveService(MailboxConnector connector, MailIngestWriter writer, MailCursorRepository cursorRepository,
                              MailTelegramNotifier notifier,
                              @Value("${mail.imap.enabled:false}") boolean enabled,
                              @Value("${mail.imap.market:KZ}") String market,
                              @Value("${mail.imap.since-minutes:60}") long sinceMinutes) {
        this(connector, writer, cursorRepository, notifier, enabled, Market.fromHeader(market), sinceMinutes, Clock.systemUTC());
    }

    MailReceiveService(MailboxConnector connector, MailIngestWriter writer, MailCursorRepository cursorRepository,
                       MailTelegramNotifier notifier, boolean enabled, Market mailboxMarket, long sinceMinutes, Clock clock) {
        this.connector = connector;
        this.writer = writer;
        this.cursorRepository = cursorRepository;
        this.notifier = notifier;
        this.enabled = enabled;
        this.mailboxMarket = mailboxMarket;
        this.sinceMinutes = sinceMinutes;
        this.clock = clock;
    }

    public Market getMailboxMarket() {
        return mailboxMarket;
    }

    public PollResultResponse poll() {
        PollResultResponse result = new PollResultResponse();
        if (!enabled) {
            result.setEnabled(false);
            result.setMessage("Приём почты выключен (MAIL_IMAP_ENABLED=false)");
            return result;
        }
        result.setEnabled(true);
        String failed = null;                                       // ящик не открылся или проход упал — весь текст
        String stopped = null;                                      // проход остановлен сбоем (§3.4) — причина
        try (MailboxSession session = connector.open()) {
            stopped = pass(session, result);
        } catch (Exception e) {
            if (InfrastructureFailure.test(e)) {                   // курсор не прочитался или не встал — это база
                stopped = MailFolderPass.dbDown(e);
                log.warn("База недоступна — проход приёма почты остановлен, повтор следующим: {}", e.getClass().getSimpleName());
            } else {
                log.warn("Ошибка приёма почты: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                // в ответ — свои тексты (§3.4): текст ПОЧТЫ («AUTHENTICATIONFAILED», «Couldn't connect…») — можно,
                // текст чужого исключения — нет: в нём бывают SQL, значения, адреса
                failed = e instanceof MessagingException
                        ? "Ошибка подключения к почте: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        : "Ошибка приёма почты: внутренняя ошибка (" + e.getClass().getSimpleName() + ") — подробности в логе сервера";
            }
        }
        MailTelegramNotifier.FlushResult tg = flush();              // и при недоступной почте: очередь не ждёт IMAP
        result.setTelegramSent(tg.sent());
        result.setTelegramPending(tg.pending());
        result.setOk(failed == null && stopped == null);
        result.setMessage(summary(result, failed, stopped, tg));
        return result;
    }

    /** null — проход дошёл до конца; иначе — почему остановлен. */
    private String pass(MailboxSession s, PollResultResponse result) throws MessagingException {
        return MailFolderPass.run(s, new MailFolderPass.Target<MailIngestWriter.WriteResult>() {
            @Override public Optional<MailCursor> cursor() {
                return cursorRepository.findById(MailCursorKey.inbox(writer.mailbox()));
            }
            @Override public MailIngestWriter.WriteResult write(ParsedMail m, long uidValidity) {
                return writer.write(m, uidValidity);
            }
            @Override public void writeBroken(BrokenMail b, long uidValidity) { writer.writeBroken(b, uidValidity); }
            @Override public void moveCursorTo(long uidValidity, long uid) { writer.moveCursorTo(uidValidity, uid); }
            @Override public void written(MailIngestWriter.WriteResult w) { count(result, w); }
            @Override public void broken() {
                result.setBroken(result.getBroken() + 1);
                result.setFetched(result.getFetched() + 1);
            }
        }, clock.instant().minus(Duration.ofMinutes(sinceMinutes)), MAX_PER_PASS, clock, log, "");
    }

    /**
     * Отправка очереди уведомлений. Её сбой (очередь — строки «Входящих», то есть база) не должен съесть итог прохода
     * по почте; в лог — только класс: проход повторяется раз в минуту.
     */
    private MailTelegramNotifier.FlushResult flush() {
        try {
            return notifier.flush();
        } catch (RuntimeException e) {
            log.warn("Очередь уведомлений в Telegram недоступна: {}", e.getClass().getSimpleName());
            return new MailTelegramNotifier.FlushResult(0, 0, "Telegram: очередь недоступна (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void count(PollResultResponse r, MailIngestWriter.WriteResult w) {
        if (w.duplicate()) return;
        switch (w.mailClass()) {
            case SITE_NOTIFICATION -> {
                r.setSkippedSiteNotifications(r.getSkippedSiteNotifications() + 1);
                return;
            }
            case SUPPLIER_RESPONSE -> r.setSupplierResponses(r.getSupplierResponses() + 1);
            case BOUNCE -> r.setBounces(r.getBounces() + 1);
            case DELAYED -> r.setDelayed(r.getDelayed() + 1);
            case AUTO_REPLY -> r.setAutoReplies(r.getAutoReplies() + 1);
            case CLIENT_REQUEST -> r.setClientRequests(r.getClientRequests() + 1);
            default -> r.setUnmatched(r.getUnmatched() + 1);
        }
        r.setFetched(r.getFetched() + 1);
    }

    /**
     * Ответ «Проверить почту»: failed — проход не состоялся (текст целиком, без счётчиков); иначе счётчики и, если проход
     * остановлен, его причина; в конце — Telegram, если там что-то было.
     */
    static String summary(PollResultResponse r, String failed, String stopped, MailTelegramNotifier.FlushResult tg) {
        StringBuilder s = new StringBuilder();
        if (failed != null) {
            s.append(failed);
        } else {
            s.append("Новых писем: ").append(r.getFetched());
            List<String> parts = new ArrayList<>();
            if (r.getSupplierResponses() > 0) parts.add("ответов поставщиков — " + r.getSupplierResponses());
            if (r.getBounces() > 0) parts.add("не доставлено — " + r.getBounces());
            if (r.getDelayed() > 0) parts.add("задерживается — " + r.getDelayed());
            if (r.getAutoReplies() > 0) parts.add("автоответов — " + r.getAutoReplies());
            if (r.getClientRequests() > 0) parts.add("писем клиник — " + r.getClientRequests());
            if (r.getUnmatched() > 0) parts.add("прочих — " + r.getUnmatched());
            if (r.getBroken() > 0) parts.add("не разобрано — " + r.getBroken());
            if (!parts.isEmpty()) s.append(" (").append(String.join(", ", parts)).append(')');
            if (r.getSkippedSiteNotifications() > 0) {
                s.append("; уведомлений сайта о заявках пропущено: ").append(r.getSkippedSiteNotifications());
            }
            if (stopped != null) s.append("; ").append(stopped).append(" — проход остановлен, повтор следующим проходом");
        }
        if (tg.sent() > 0 || tg.pending() > 0 || tg.lastError() != null) {
            s.append("; Telegram: отправлено ").append(tg.sent()).append(", ждут ").append(tg.pending());
            if (tg.lastError() != null) s.append(" — ").append(tg.lastError());
        }
        return s.toString();
    }
}
