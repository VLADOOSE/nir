package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.MailCursor;
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
    /** Причина остановки прохода — свой текст для ответа «Проверить почту» (спека §3.4). */
    private static final String CONNECTION_LOST = "связь с почтой оборвалась";

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
        String imapError = null;                                    // ящик не открылся или не ответил
        String stopped = null;                                      // проход остановлен сбоем (§3.4)
        try (MailboxSession session = connector.open()) {
            stopped = pass(session, result);
        } catch (Exception e) {
            if (InfrastructureFailure.test(e)) {                   // курсор не прочитался или не встал — это база
                stopped = dbDown(e);
                log.warn("База недоступна — проход приёма почты остановлен, повтор следующим: {}", e.getClass().getSimpleName());
            } else {
                imapError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("Ошибка приёма почты: {}", imapError);
            }
        }
        MailTelegramNotifier.FlushResult tg = flush();              // и при недоступной почте: очередь не ждёт IMAP
        result.setTelegramSent(tg.sent());
        result.setTelegramPending(tg.pending());
        result.setOk(imapError == null && stopped == null);
        result.setMessage(summary(result, imapError, stopped, tg));
        return result;
    }

    /** null — проход дошёл до конца; иначе — почему остановлен. */
    private String pass(MailboxSession s, PollResultResponse result) throws MessagingException {
        long uidValidity = s.uidValidity();
        Optional<MailCursor> cursor = cursorRepository.findById(writer.mailbox())
                .filter(c -> c.getUidValidity() == uidValidity);
        if (cursor.isPresent()) {
            for (long uid : s.uidsAfter(cursor.get().getLastUid(), MAX_PER_PASS)) {
                String stopped = processOne(s, uidValidity, uid, result);
                if (stopped != null) return stopped;
            }
            return null;
        }
        // Первый запуск: только окно since-minutes, затем курсор — на последнее письмо, снятое ДО окна (письмо,
        // пришедшее во время прохода, получит UID больше и уйдёт следующим проходом, а не перепрыгнется).
        long max = s.maxUid();
        for (long uid : s.uidsReceivedSince(clock.instant().minus(Duration.ofMinutes(sinceMinutes)), MAX_PER_PASS)) {
            if (uid > max) continue;
            String stopped = processOne(s, uidValidity, uid, result);
            if (stopped != null) return stopped;
        }
        writer.moveCursorTo(uidValidity, max);
        return null;
    }

    /**
     * null — дальше; иначе проход остановить (спека §3.4): связь с ящиком оборвалась, база недоступна или письмо не
     * записалось даже коротко. Курсор при этом стоит на последнем записанном письме — повтор следующим проходом.
     */
    private String processOne(MailboxSession s, long uidValidity, long uid, PollResultResponse result) {
        ParsedMail m;
        try {
            m = s.fetch(uid);
        } catch (Exception e) {
            if (!s.isAlive()) {
                log.warn("Связь с ящиком оборвалась на письме UID {}: {}", uid, e.getClass().getSimpleName());
                return CONNECTION_LOST;
            }
            log.warn("Письмо UID {} не разобрано — записываю коротко", uid, e);
            return writeBroken(s.envelope(uid, e), uidValidity, result);
        }
        if (m == null) return null;                                 // письмо удалили между поиском и чтением
        MailIngestWriter.WriteResult written;
        try {
            written = writer.write(m, uidValidity);
        } catch (RuntimeException e) {
            if (InfrastructureFailure.test(e)) {
                log.warn("База недоступна на письме UID {} — проход остановлен, повтор следующим: {}", uid, e.getClass().getSimpleName());
                return dbDown(e);
            }
            log.warn("Письмо UID {} не записано — записываю коротко", uid, e);
            return writeBroken(new BrokenMail(uid, m.messageId(), m.from(), m.subject(), m.receivedAt(),
                    e.getClass().getSimpleName()), uidValidity, result);
        }
        count(result, written);                                     // вне try: записанное письмо коротко не пишем
        return null;
    }

    private String writeBroken(BrokenMail b, long uidValidity, PollResultResponse result) {
        try {
            writer.writeBroken(b, uidValidity);
            result.setBroken(result.getBroken() + 1);
            result.setFetched(result.getFetched() + 1);
            return null;
        } catch (RuntimeException e) {
            log.warn("Письмо UID {} не записано даже коротко — проход остановлен: {}", b.uid(), e.getClass().getSimpleName());
            return InfrastructureFailure.test(e) ? dbDown(e) : "письмо не записалось (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String dbDown(Exception e) {
        return "база данных недоступна (" + e.getClass().getSimpleName() + ")";
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
            case AUTO_REPLY -> r.setAutoReplies(r.getAutoReplies() + 1);
            case CLIENT_REQUEST -> r.setClientRequests(r.getClientRequests() + 1);
            default -> r.setUnmatched(r.getUnmatched() + 1);
        }
        r.setFetched(r.getFetched() + 1);
    }

    static String summary(PollResultResponse r, String imapError, String stopped, MailTelegramNotifier.FlushResult tg) {
        StringBuilder s = new StringBuilder();
        if (imapError != null) {
            s.append("Ошибка подключения к почте: ").append(imapError);
        } else {
            s.append("Новых писем: ").append(r.getFetched());
            List<String> parts = new ArrayList<>();
            if (r.getSupplierResponses() > 0) parts.add("ответов поставщиков — " + r.getSupplierResponses());
            if (r.getBounces() > 0) parts.add("не доставлено — " + r.getBounces());
            if (r.getAutoReplies() > 0) parts.add("автоответов — " + r.getAutoReplies());
            if (r.getClientRequests() > 0) parts.add("писем клиник — " + r.getClientRequests());
            if (r.getUnmatched() > 0) parts.add("прочих — " + r.getUnmatched());
            if (r.getBroken() > 0) parts.add("не разобрано — " + r.getBroken());
            if (!parts.isEmpty()) s.append(" (").append(String.join(", ", parts)).append(')');
            if (r.getSkippedSiteNotifications() > 0) {
                s.append("; уведомлений сайта о заявках пропущено: ").append(r.getSkippedSiteNotifications());
            }
            if (stopped != null) s.append("; проход остановлен: ").append(stopped).append(" — повтор следующим проходом");
        }
        if (tg.sent() > 0 || tg.pending() > 0 || tg.lastError() != null) {
            s.append("; Telegram: отправлено ").append(tg.sent()).append(", ждут ").append(tg.pending());
            if (tg.lastError() != null) s.append(" — ").append(tg.lastError());
        }
        return s.toString();
    }
}
