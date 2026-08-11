package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.integration.goszakup.ImportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Оркестрация импорта СК-Фармации (fms.ecc.kz), зеркалит GoszakupImportService, но HTML-скрейп.
 * Ступень-1 (имя) до fetch лотов; ступень-2 (лоты) → device-only тендеры; fail-soft на объявление.
 */
@Service
public class SkPharmacyImportService {

    private static final Logger log = LoggerFactory.getLogger(SkPharmacyImportService.class);

    private final SkPharmacyClient client;
    private final SkPharmacyTenderWriter writer;
    private final int maxPages;
    private final int maxLotPages;
    private final long throttleMs;

    public SkPharmacyImportService(SkPharmacyClient client, SkPharmacyTenderWriter writer,
                                   @Value("${skpharmacy.import.max-pages:30}") int maxPages,
                                   @Value("${skpharmacy.import.max-lot-pages:20}") int maxLotPages,
                                   @Value("${skpharmacy.import.throttle-ms:300}") long throttleMs) {
        this.client = client;
        this.writer = writer;
        this.maxPages = maxPages;
        this.maxLotPages = maxLotPages;
        this.throttleMs = throttleMs;
    }

    /**
     * Лоты объявления со ВСЕХ страниц вкладки. Идём вперёд, пока пейджер даёт ссылку на следующую страницу,
     * и обрываемся на странице без НОВЫХ кодов лотов: за последней страницей портал отдаёт контент последней
     * (page=4 = page=3) и «вперёд» обещать не перестаёт, так что одного признака мало. Предел `max-lot-pages` —
     * последний рубеж; троттлинг между страницами тот же, что между объявлениями (бан площадки).
     */
    /**
     * Лоты объявления + признак «это закупка УСЛУГ». Признак снимается с ПЕРВОЙ страницы: услуги и товары
     * в одном объявлении не смешиваются, вкладка целиком одной вёрстки.
     */
    private record FetchedLots(List<SkLot> lots, boolean services) {}

    private FetchedLots fetchLots(String announceId) {
        List<SkLot> all = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        boolean services = false;
        for (int page = 1; page <= maxLotPages; page++) {
            String html = client.lotsPage(announceId, page);
            if (page == 1) services = SkPharmacyHtmlParser.isServicesLotsPage(html);
            List<SkLot> pageLots = SkPharmacyHtmlParser.parseLots(html);
            int added = 0;
            for (SkLot l : pageLots) {
                if (seenCodes.add(l.code())) { all.add(l); added++; }
            }
            if (added == 0) break;                                          // страница без новых лотов — пейджер зациклен
            if (!SkPharmacyHtmlParser.hasNextLotsPage(html, page)) break;   // последняя страница
            if (!throttle()) break;
        }
        return new FetchedLots(all, services);
    }

    /** Вкладка «Общие сведения» — доп. запрос к порталу; регион/контакт вторичны → сбой не валит тендер (пишем без них). */
    private SkGeneral fetchGeneral(SkAnnounce a) {
        try {
            return SkPharmacyHtmlParser.parseGeneral(client.generalPage(a.announceId()));
        } catch (Exception e) {
            log.warn("sk general {}: {}", a.numberAnno(), e.getMessage());
            return null;
        }
    }

    /** Пауза между запросами к порталу (троттлинг от бана). Прерывание → сигнал остановить прогон. */
    private boolean throttle() {
        if (throttleMs <= 0) return true;
        try { Thread.sleep(throttleMs); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /** Наполняет переданный ImportSummary по ходу (живой прогресс, как goszakup). */
    public void fillImport(ImportSummary sum) {
        sum.setMaxPages(maxPages);
        for (int page = 1; page <= maxPages; page++) {
            List<SkAnnounce> anns;
            try {
                anns = SkPharmacyHtmlParser.parseSearch(client.searchPage(page));
            } catch (Exception e) {
                log.warn("sk searchanno стр. {}: {}", page, e.getMessage());
                sum.setErrors(sum.getErrors() + 1);
                break;   // сеть/бан по списку — дальше не идём
            }
            sum.setPagesRead(page);
            if (anns.isEmpty()) break;   // конец ленты

            for (SkAnnounce a : anns) {
                sum.setFetched(sum.getFetched() + 1);
                if (!throttle()) { sum.setMessage("Импорт прерван"); return; }   // троттлинг + чистая остановка
                try {
                    if (!SkPharmacyRelevanceFilter.nameCandidate(a.nameRu())) {   // ступень 1 — явные лекарства
                        sum.setSkipped(sum.getSkipped() + 1);
                        continue;
                    }
                    FetchedLots fetched = fetchLots(a.announceId());
                    if (fetched.services()) {          // закупка медпомощи ГОБМП/ОСМС — не наш предмет вовсе
                        sum.setSkipped(sum.getSkipped() + 1);
                        continue;
                    }
                    List<SkLot> lots = fetched.lots();
                    List<String> lotNames = lots.stream().map(SkLot::name).toList();
                    if (!SkPharmacyRelevanceFilter.isRelevant(a.nameRu(), lotNames)) {   // ступень 2 — по лотам
                        sum.setSkipped(sum.getSkipped() + 1);
                        continue;
                    }
                    sum.setMatched(sum.getMatched() + 1);
                    SkGeneral general = fetchGeneral(a);   // регион/БИН/контакт со вкладки «Общие сведения» — fail-soft
                    if (writer.upsert(a, lots, general) == SkPharmacyTenderWriter.Result.CREATED) {
                        sum.setCreated(sum.getCreated() + 1);
                    } else {
                        sum.setUpdated(sum.getUpdated() + 1);
                    }
                } catch (Exception e) {
                    log.warn("sk объявление {}: {}", a.numberAnno(), e.getMessage());
                    sum.setErrors(sum.getErrors() + 1);
                }
            }
        }
        sum.setMessage("СК-Фармация: стр. " + sum.getPagesRead() + ", получено " + sum.getFetched()
                + ", подходящих " + sum.getMatched() + ", создано " + sum.getCreated()
                + ", обновлено " + sum.getUpdated() + (sum.getErrors() > 0 ? ", ошибок " + sum.getErrors() : ""));
    }
}
