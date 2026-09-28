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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final GreenApiClient client;
    private final ChatIngestWriter writer;
    private final WestmedClient westmedClient;
    private final WhatsappStatusHolder status;
    private final String siteUrl;
    private final long maxFileBytes;
    private final int receiveTimeoutSec;
    /** receiptId → сколько раз подряд не приняли. В памяти: после рестарта счёт с нуля — это ≤ 2 лишние попытки. */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();

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
            if (r == null) return true;
            if (!process(r)) return false;
        }
        return true;
    }

    /** Одно уведомление. false — не принято и оставлено в очереди (Green-API отдаст его снова — голову очереди). */
    boolean process(GreenApiReceived r) {
        int attempt = failures.getOrDefault(r.receiptId(), 0) + 1;
        try {
            handle(GreenApiNotificationParser.parse(r.body()), attempt);
        } catch (GreenApiAuthException | GreenApiQuotaException e) {
            throw e;   // беда инстанса, а не уведомления: паузу ставит планировщик, попытку не считаем
        } catch (RuntimeException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (attempt < MAX_ATTEMPTS) {
                failures.put(r.receiptId(), attempt);
                status.setLastError("сообщение не принято (попытка " + attempt + " из " + MAX_ATTEMPTS + "): " + reason);
                return false;
            }
            // «ядовитое» уведомление не должно навсегда забить голову очереди
            status.messageDropped();
            status.setLastError("сообщение пропущено после " + MAX_ATTEMPTS + " попыток: " + reason);
            log.warn("WhatsApp: уведомление {} не принято {} раз подряд — удалено из очереди: {}", r.receiptId(), attempt, reason);
        }
        failures.remove(r.receiptId());
        client.delete(r.receiptId());
        return true;
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
