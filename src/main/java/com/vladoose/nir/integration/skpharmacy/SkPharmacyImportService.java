package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.util.ErrorText;
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
    private UpstreamRetry.Sleeper sleeper = UpstreamRetry.REAL;

    public SkPharmacyImportService(SkPharmacyClient client, SkPharmacyTenderWriter writer,
                                   @Value("${skpharmacy.import.max-pages:30}") int maxPages,
                                   @Value("${skpharmacy.import.max-lot-pages:200}") int maxLotPages,
                                   @Value("${skpharmacy.import.throttle-ms:300}") long throttleMs) {
        this.client = client;
        this.writer = writer;
        this.maxPages = maxPages;
        this.maxLotPages = maxLotPages;
        this.throttleMs = throttleMs;
    }

    /** Тесты подменяют сон пауз повторов (1 и 3 с), не заводя отдельный Spring-контекст. */
    void setSleeper(UpstreamRetry.Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * Лоты объявления + признак «это закупка УСЛУГ» + признак «список НЕПОЛНЫЙ». Признак услуг снимается с ПЕРВОЙ
     * страницы: услуги и товары в одном объявлении не смешиваются, вкладка целиком одной вёрстки.
     * {@code truncated} — обход оборвался, не дочитав вкладку: упор в {@code max-lot-pages} при живой ссылке
     * «вперёд» или страница N&gt;1 (обещанная пейджером предыдущей) разобралась в 0 лотов — троттлинг, страница
     * ошибки. По неполному списку райтер лоты не удаляет.
     */
    private record FetchedLots(List<SkLot> lots, boolean services, boolean truncated) {}

    /**
     * Лоты объявления со ВСЕХ страниц вкладки. Идём вперёд, пока пейджер даёт ссылку на следующую страницу,
     * и обрываемся на странице без НОВЫХ кодов лотов: за последней страницей портал отдаёт контент последней
     * (page=4 = page=3) и «вперёд» обещать не перестаёт, так что одного признака мало. Предел `max-lot-pages` —
     * последний рубеж (упор в него = неполный список); троттлинг между страницами тот же, что между объявлениями.
     */
    private FetchedLots fetchLots(String announceId) {
        List<SkLot> all = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        boolean services = false;
        boolean truncated = false;
        for (int page = 1; ; page++) {
            if (page > maxLotPages) { truncated = true; break; }           // предыдущая страница обещала следующую
            int p = page;
            String html = UpstreamRetry.call(sleeper, () -> client.lotsPage(announceId, p));
            if (page == 1) services = SkPharmacyHtmlParser.isServicesLotsPage(html);
            List<SkLot> pageLots = SkPharmacyHtmlParser.parseLots(html);
            if (page > 1 && pageLots.isEmpty()) { truncated = true; break; }   // обещанная страница пришла без таблицы
            int added = 0;
            for (SkLot l : pageLots) {
                if (seenCodes.add(l.code())) { all.add(l); added++; }
            }
            if (added == 0) break;                                          // страница без новых лотов — пейджер зациклен
            if (!SkPharmacyHtmlParser.hasNextLotsPage(html, page)) break;   // последняя страница
            if (!throttle()) { truncated = true; break; }                   // остановка прогона посреди вкладки
        }
        return new FetchedLots(all, services, truncated);
    }

    /** Вкладка «Общие сведения» — доп. запрос к порталу; регион/контакт вторичны → сбой не валит тендер (пишем без них). */
    private SkGeneral fetchGeneral(SkAnnounce a) {
        try {
            return SkPharmacyHtmlParser.parseGeneral(UpstreamRetry.call(sleeper, () -> client.generalPage(a.announceId())));
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
                int p = page;
                anns = SkPharmacyHtmlParser.parseSearch(UpstreamRetry.call(sleeper, () -> client.searchPage(p)));
            } catch (Exception e) {
                log.warn("sk searchanno стр. {}: {}", page, ErrorText.of(e));
                sum.addError("лента, стр. " + page + ": " + ErrorText.of(e));
                break;   // сеть/бан по списку — дальше не идём
            }
            sum.setPagesRead(page);
            if (anns.isEmpty()) {
                // У портала тысячи объявлений: пустая ПЕРВАЯ страница — не конец ленты, а сменившаяся вёрстка
                // или страница-заглушка; без ошибки прогон молча закончился бы «получено 0».
                if (page == 1) sum.addError("на первой странице ленты нет объявлений — похоже, сменилась вёрстка fms.ecc.kz");
                break;   // конец ленты
            }

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
                    Integer expected = a.lotsCount();
                    int got = lots.size();
                    // ДО фильтра по лотам: пустой список ушёл бы там в фолбэк по имени объявления и тихо записался
                    // бы тендер без лотов
                    if (got == 0 && expected != null && expected > 0) {
                        sum.addError("объявление " + a.numberAnno() + ": лоты не разобраны — на площадке "
                                + expected + ", получено 0");
                        continue;
                    }
                    // лента и вкладка сверены живьём (measurements.md, Task 8): число в ленте = строкам вкладки
                    boolean complete = !fetched.truncated() && (expected == null || got >= expected);
                    List<String> lotNames = lots.stream().map(SkLot::name).toList();
                    if (!SkPharmacyRelevanceFilter.isRelevant(a.nameRu(), lotNames)) {   // ступень 2 — по лотам
                        sum.setSkipped(sum.getSkipped() + 1);
                        continue;
                    }
                    sum.setMatched(sum.getMatched() + 1);
                    SkGeneral general = fetchGeneral(a);   // регион/БИН/контакт со вкладки «Общие сведения» — fail-soft
                    if (writer.upsert(a, lots, general, complete) == SkPharmacyTenderWriter.Result.CREATED) {
                        sum.setCreated(sum.getCreated() + 1);
                    } else {
                        sum.setUpdated(sum.getUpdated() + 1);
                    }
                    if (!complete) {
                        // «больше», если число ленты неизвестно или (обрыв пагинации) не меньше полученного
                        String onPortal = expected == null || got >= expected ? "больше" : String.valueOf(expected);
                        sum.addError("объявление " + a.numberAnno() + ": на площадке " + onPortal
                                + " лотов, получено " + got + " — лишние лоты не удалялись");
                    }
                } catch (Exception e) {
                    log.warn("sk объявление {}: {}", a.numberAnno(), ErrorText.of(e));
                    sum.addError("объявление " + a.numberAnno() + ": " + ErrorText.of(e));
                }
            }
        }
        sum.setMessage("Стр. " + sum.getPagesRead() + ", получено " + sum.getFetched()
                + ", подходящих " + sum.getMatched() + ", создано " + sum.getCreated()
                + ", обновлено " + sum.getUpdated() + (sum.getErrors() > 0 ? ", ошибок " + sum.getErrors() : ""));
    }
}
