package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadStatusPushWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Один цикл синхронизации с westmed.kz (спека §6.2–6.3): забрать новые заявки, затем записать
 * ожидающие статусы. Сам НЕ транзакционный — сеть вне транзакций; БД — через бины intake/pushWriter.
 * MarketContext ставит вызывающий (WestmedLeadScheduler).
 */
@Service
public class WestmedLeadSync {

    private static final Logger log = LoggerFactory.getLogger(WestmedLeadSync.class);

    /** Предохранитель от бесконечного листания при кривом ответе сайта. */
    static final int MAX_PAGES = 200;

    private final WestmedClient client;
    private final WestmedLeadMapper mapper;
    private final LeadIntakeService intake;
    private final LeadStatusPushWriter pushWriter;
    private final int pageSize;
    private final boolean writeStatus;

    public WestmedLeadSync(WestmedClient client, WestmedLeadMapper mapper, LeadIntakeService intake,
                           LeadStatusPushWriter pushWriter,
                           @Value("${leads.westmed.page-size:50}") int pageSize,
                           @Value("${leads.westmed.write-status:false}") boolean writeStatus) {
        this.client = client;
        this.mapper = mapper;
        this.intake = intake;
        this.pushWriter = pushWriter;
        this.pageSize = pageSize;
        this.writeStatus = writeStatus;
    }

    public WestmedSyncResult runOnce() {
        WestmedSyncResult res = new WestmedSyncResult();
        WestmedProductLookup lookup = new WestmedProductLookup(client);   // кеш каталога — на этот цикл
        pull(res, page -> client.fetchPriceRequests(page, pageSize), WestmedPriceRequest::id,
                WestmedKind.PRICE, r -> mapper.fromPriceRequest(r, lookup));
        pull(res, page -> client.fetchQuoteRequests(page, pageSize), WestmedQuoteRequest::id,
                WestmedKind.QUOTE, r -> mapper.fromQuoteRequest(r, lookup));
        if (writeStatus) push(res);
        return res;
    }

    /**
     * Листаем от новых к старым; страница без единой новой заявки — стоп. Одно правило даёт и импорт
     * всей истории на первом запуске, и дешёвый инкремент потом. Известные заявки НЕ маппим — иначе
     * каждые 90 с ходили бы в каталог за брендами уже принятых позиций.
     */
    private <T> void pull(WestmedSyncResult res, IntFunction<WestmedPage<T>> fetch, Function<T, String> siteIdOf,
                          WestmedKind kind, Function<T, IncomingLead> toLead) {
        for (int page = 0; page < MAX_PAGES; page++) {
            WestmedPage<T> p = fetch.apply(page);
            List<T> content = p == null ? List.of() : p.contentOrEmpty();
            int fresh = 0;
            for (T r : content) {
                String externalId = kind.externalId(siteIdOf.apply(r));
                if (intake.isKnown(LeadSources.WESTMED, externalId)) continue;
                fresh++;
                try {
                    if (intake.ingest(toLead.apply(r)).isPresent()) res.created++;
                } catch (DataIntegrityViolationException e) {
                    // гонка вставок: обращение уже создано — это не ошибка (спека §7)
                } catch (RuntimeException e) {
                    res.errors++;
                    res.lastError = "заявка " + externalId + ": " + e.getMessage();
                    log.warn("westmed.kz: заявка {} не принята: {}", externalId, e.getMessage());
                }
            }
            if (fresh == 0 || p == null || p.isLast()) break;
        }
    }

    /** Отказ входа (WestmedAuthException) пробрасывается — планировщик ставит паузу; ожидание остаётся. */
    private void push(WestmedSyncResult res) {
        for (LeadStatusPushWriter.PendingPush p : pushWriter.findPending(LeadSources.WESTMED)) {
            try {
                client.updateStatus(WestmedKind.ofExternalId(p.externalId()), WestmedKind.siteIdOf(p.externalId()), p.status());
                pushWriter.markPushed(p.leadId(), p.status());
                res.pushed++;
            } catch (WestmedApiException e) {
                if (e.status() == 404) {
                    pushWriter.markGone(p.leadId());
                } else {
                    pushWriter.markFailed(p.leadId(), "не удалось записать статус на сайт: " + e.getMessage());
                    res.errors++;
                    res.lastError = e.getMessage();
                }
            }
        }
    }
}
