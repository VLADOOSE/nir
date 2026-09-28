package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.entity.AttachmentNotStoredReason;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.WestmedClient;
import com.vladoose.nir.integration.westmed.WestmedProductLookup;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.util.SiteCartMessageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проход по очереди Green-API (спека whatsapp-chats §6): получить → (скачать файл, найти бренды корзины — ВНЕ
 * транзакции) → записать (ChatIngestWriter, своя транзакция) → удалить из очереди. Удаляем ТОЛЬКО после записи:
 * сбой посередине → уведомление придёт снова, дубль отсечёт writer. Сам НЕ транзакционный; MarketContext ставит
 * вызывающий (WhatsappChatScheduler).
 */
@Service
public class WhatsappChatSync {

    private static final Logger log = LoggerFactory.getLogger(WhatsappChatSync.class);
    static final int MAX_ATTEMPTS = 3;
    /**
     * Столько «ядовитых» ЗА СУТКИ — это уже не битое уведомление, а поломка (регрессия, ломающая запись): дальше не
     * удаляем, стоим с красной строкой — новые сообщения ждут в очереди Green-API (сутки). Именно за сутки, а не
     * подряд: успешные служебные уведомления между «ядовитыми» (статусы доставки, группы) сбрасывали бы серию,
     * и регрессия, ломающая только личные сообщения, выкидывала бы очередь по одному (перепроверка ревью).
     */
    static final int DROP_FUSE = 3;
    /** Предел глубины цепочки причин — от циклов, которые не самоссылка. */
    private static final int MAX_CAUSE_DEPTH = 32;
    /** Классы SQLSTATE: 08 соединение, 40 откат (deadlock), 53 ресурсы (диск/память), 57 вмешательство, 58 система. */
    private static final Set<String> INFRA_SQLSTATE_CLASSES = Set.of("08", "40", "53", "57", "58");

    private final GreenApiClient client;
    private final ChatIngestWriter writer;
    private final WestmedClient westmedClient;
    private final WhatsappStatusHolder status;
    private final String siteUrl;
    private final long maxFileBytes;
    private final int receiveTimeoutSec;
    /** receiptId → сколько раз подряд не приняли. В памяти: после рестарта счёт с нуля — это ≤ 2 лишние попытки. */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();
    /**
     * Состояния «база недоступна» и «приём остановлен» повторяются каждым проходом (раз в 30–60 с): в лог — один раз
     * на вход в состояние, иначе тысячи трасс в сутки. Сбрасывает успешное уведомление. Пишет один поток приёма.
     */
    private boolean infraLogged;
    private boolean haltLogged;

    public WhatsappChatSync(GreenApiClient client, ChatIngestWriter writer, WestmedClient westmedClient,
                            WhatsappStatusHolder status,
                            @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl,
                            @Value("${chats.whatsapp.max-file-mb:25}") int maxFileMb,
                            @Value("${chats.whatsapp.receive-timeout-s:20}") int receiveTimeoutSec) {
        this.client = client;
        this.writer = writer;
        this.westmedClient = westmedClient;
        this.status = status;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.maxFileBytes = maxFileMb * 1024L * 1024L;
        this.receiveTimeoutSec = receiveTimeoutSec;
    }

    /** До maxNotifications уведомлений или до пустой очереди. false — проход оборван сбоем уведомления (нужна пауза). */
    public boolean drain(int maxNotifications) {
        for (int i = 0; i < maxNotifications; i++) {
            GreenApiReceived r = client.receive(receiveTimeoutSec);
            status.progress();
            if (r == null) return true;
            if (!process(r)) return false;
            status.progress();
        }
        return true;
    }

    /**
     * Одно уведомление. false — не принято и оставлено в очереди (Green-API отдаст его снова — голову очереди).
     * Сбой базы/диска — не вина уведомления: попытку не считаем. «Ядовитое» (упало MAX_ATTEMPTS раз) удаляем,
     * чтобы оно не забило голову очереди навсегда, — но не больше DROP_FUSE за сутки.
     */
    boolean process(GreenApiReceived r) {
        int attempt = failures.getOrDefault(r.receiptId(), 0) + 1;
        try {
            handle(GreenApiNotificationParser.parse(r.body()), attempt);
            infraLogged = false;
            haltLogged = false;
        } catch (RuntimeException e) {
            if (isInfrastructureFailure(e)) {
                status.setLastError("приём приостановлен: база данных недоступна (" + e.getClass().getSimpleName()
                        + ") — сообщения ждут в очереди Green-API");
                if (!infraLogged) {
                    log.warn("WhatsApp: база данных недоступна — уведомление {} ждёт в очереди", r.receiptId(), e);
                    infraLogged = true;
                }
                return false;
            }
            String reason = reason(e);
            if (attempt < MAX_ATTEMPTS) {
                failures.put(r.receiptId(), attempt);
                status.setLastError("сообщение не принято (попытка " + attempt + " из " + MAX_ATTEMPTS + "): " + reason);
                log.warn("WhatsApp: уведомление {} не принято (попытка {} из {})", r.receiptId(), attempt, MAX_ATTEMPTS, e);
                return false;
            }
            if (status.recentDrops() >= DROP_FUSE) {
                failures.put(r.receiptId(), attempt);
                status.setLastError("приём остановлен: за сутки не записались " + status.recentDrops()
                        + " сообщения — нужна проверка (новые ждут в очереди Green-API до суток): " + reason);
                if (!haltLogged) {
                    log.error("WhatsApp: за сутки не записались {} уведомления — приём остановлен, {} оставлено в очереди",
                            status.recentDrops(), r.receiptId(), e);
                    haltLogged = true;
                }
                return false;
            }
            status.messageDropped();
            status.setLastError("сообщение пропущено после " + MAX_ATTEMPTS + " попыток: " + reason);
            log.warn("WhatsApp: уведомление {} не принято {} раз подряд — удалено из очереди", r.receiptId(), attempt, e);
        }
        failures.remove(r.receiptId());
        client.delete(r.receiptId());
        return true;
    }

    /**
     * Текст для строки состояния, которую видит любой вошедший: свои сообщения Green-API — как есть (собраны без
     * адреса), чужие исключения — только класс: в их тексте бывают SQL, значения и адреса.
     */
    static String reason(Throwable e) {
        if (e instanceof GreenApiException) return e.getMessage();
        return "внутренняя ошибка (" + e.getClass().getSimpleName() + ") — подробности в логе сервера";
    }

    /** Сбой инфраструктуры (база, диск, соединение), а не битое уведомление — по всей цепочке причин. */
    static boolean isInfrastructureFailure(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataAccessResourceFailureException || t instanceof CannotCreateTransactionException
                    || t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().length() >= 2
                    && INFRA_SQLSTATE_CLASSES.contains(sql.getSQLState().substring(0, 2))) {
                return true;
            }
        }
        return false;
    }

    private void handle(ParsedNotification p, int attempt) {
        if (p instanceof ParsedNotification.Message m) {
            IncomingFile file = fetchFile(m, attempt);
            writer.write(m, file, cartItems(m));
            status.messageSeen(m.sentAt());
        } else if (p instanceof ParsedNotification.Delete d) {
            writer.applyDelete(d);
        } else if (p instanceof ParsedNotification.State s) {
            status.setState(s.state());
        } else if (p instanceof ParsedNotification.QuotaExceeded) {
            status.quotaExceeded();
        }
        // Skip — только подтвердить (рекомендация Green-API)
    }

    private IncomingFile fetchFile(ParsedNotification.Message m, int attempt) {
        FileRef ref = m.file();
        if (ref == null) return null;
        if (m.kind() == ChatKind.GROUP) return IncomingFile.notStored(ref, AttachmentNotStoredReason.GROUP);
        if (ref.downloadUrl() == null) return IncomingFile.notStored(ref, AttachmentNotStoredReason.DOWNLOAD_FAILED);
        try {
            return IncomingFile.stored(ref, client.download(ref.downloadUrl(), maxFileBytes));
        } catch (FileTooLargeException e) {
            return IncomingFile.notStored(ref, AttachmentNotStoredReason.TOO_LARGE);
        } catch (GreenApiException e) {
            // последняя попытка — пишем сообщение без файла: текст важнее, файл остаётся в телефоне
            if (attempt >= MAX_ATTEMPTS) return IncomingFile.notStored(ref, AttachmentNotStoredReason.DOWNLOAD_FAILED);
            throw e;
        }
    }

    /** Позиции из шаблона корзины сайта (только входящие) с брендом из каталога westmed.kz; сбой поиска — без бренда. */
    private List<IncomingLead.Item> cartItems(ParsedNotification.Message m) {
        if (m.direction() != LeadDirection.IN || m.isEdit()) return List.of();
        List<SiteCartMessageParser.Line> lines = SiteCartMessageParser.parse(m.body());
        if (lines.isEmpty()) return List.of();
        WestmedProductLookup lookup = new WestmedProductLookup(westmedClient);
        return lines.stream().map(l -> {
            Optional<WestmedProduct> p = lookup.byName(l.name());
            return new IncomingLead.Item(l.name(), p.map(WestmedProduct::brandName).orElse(null), l.quantity(),
                    p.map(x -> siteUrl + "/product/" + x.slug()).orElse(null));
        }).toList();
    }
}
