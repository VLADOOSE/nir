package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Оркеструет импорт KZ-тендеров: пагинация + фильтры + получение subject/lots по сети,
 * запись каждого тендера — через GoszakupTenderWriter (per-item @Transactional).
 * Сам НЕ транзакционный: блокирующий сетевой I/O не держит БД-коннект, а ошибка одного
 * объявления идёт в ImportSummary.errors и не валит весь прогон.
 */
@Service
public class GoszakupImportService {

    private static final Logger log = LoggerFactory.getLogger(GoszakupImportService.class);

    private final GoszakupClient client;
    private final GoszakupTenderWriter writer;
    private final KatoDictionary katoDictionary;
    private final List<String> keywords;
    private final Set<Integer> statuses;
    private final int sinceDays;
    private final int maxPages;

    public GoszakupImportService(GoszakupClient client,
                                 GoszakupTenderWriter writer,
                                 KatoDictionary katoDictionary,
                                 @Value("${goszakup.import.keywords:}") String keywordsCsv,
                                 @Value("${goszakup.import.statuses:}") String statusesCsv,
                                 @Value("${goszakup.import.since-days:30}") int sinceDays,
                                 @Value("${goszakup.import.max-pages:20}") int maxPages) {
        this.client = client;
        this.writer = writer;
        this.katoDictionary = katoDictionary;
        this.keywords = csv(keywordsCsv).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.statuses = parseStatuses(statusesCsv);
        this.sinceDays = sinceDays;
        this.maxPages = maxPages;
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
    }

    /** Лояльный разбор статусов: нечисловые токены пропускаем, чтобы кривой конфиг не ронял старт. */
    private static Set<Integer> parseStatuses(String s) {
        Set<Integer> ids = new HashSet<>();
        for (String token : csv(s)) {
            try { ids.add(Integer.valueOf(token)); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return ids;
    }

    public ImportSummary importMedicalTenders() {
        return importMedicalTenders(null);
    }

    /** region — каноническое имя области/города (как в фильтре UI) или null: вся лента. */
    public ImportSummary importMedicalTenders(String region) {
        ImportSummary sum = new ImportSummary();
        fillImport(region, sum);
        return sum;
    }

    /** Наполняет ПЕРЕДАННЫЙ summary по ходу работы — вызывающий может показывать живой прогресс. */
    public void fillImport(String region, ImportSummary sum) {
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        sum.setMaxPages(maxPages);
        LocalDate cutoff = LocalDate.now().minusDays(sinceDays);
        if (region == null || region.isBlank()) {
            fetchWholeFeed(cutoff, sum);
        } else if (!fetchRegionFeed(region, cutoff, sum)) {
            return; // регион не распознан — message уже установлен
        }
        sum.setMessage(String.format("Получено %d, релевантных %d, создано %d, обновлено %d, ошибок %d",
                sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated(), sum.getErrors()));
    }

    private void fetchWholeFeed(LocalDate cutoff, ImportSummary sum) {
        String cursor = null;
        int pagesRead = 0;
        do {
            TrdBuyPageDto page = client.fetchTrdBuyPage(cursor);
            List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
            processItems(items, cutoff, sum, null);
            pagesRead++;
            sum.setPagesRead(pagesRead);
            // лента отсортирована по id DESC: страница целиком старше cutoff → дальше только старее
            if (wholePageOlderThan(items, cutoff)) break;
            cursor = page.getNextPage();
        } while (cursor != null && !cursor.isBlank() && pagesRead < maxPages);
    }

    /** false — регион не распознан (КАТО-префикс неизвестен), импорт не выполнялся. */
    private boolean fetchRegionFeed(String region, LocalDate cutoff, ImportSummary sum) {
        List<String> katoCodes = katoDictionary.codesForRegion(region);
        if (katoCodes.isEmpty()) {
            sum.setEnabled(false);
            sum.setMessage("Не удалось определить КАТО-коды региона: " + region);
            return false;
        }
        Long after = null;
        int pagesRead = 0;
        do {
            var page = client.fetchTrdBuyPageByKato(katoCodes, after);
            List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
            processItems(items, cutoff, sum, region);
            pagesRead++;
            sum.setPagesRead(pagesRead);
            if (wholePageOlderThan(items, cutoff)) break;
            after = page.getNextAfter();
        } while (after != null && pagesRead < maxPages);
        return true;
    }

    private void processItems(List<TrdBuyDto> items, LocalDate cutoff, ImportSummary sum, String regionOverride) {
        for (TrdBuyDto d : items) {
            sum.setFetched(sum.getFetched() + 1);
            LocalDate pub = GoszakupParse.localDate(d.getPublishDate());
            if (pub != null && pub.isBefore(cutoff)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            if (!statusOk(d) || !systemOk(d) || !keywordOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            sum.setMatched(sum.getMatched() + 1); // прошёл ступень-1 (имя); ступень-2 по лотам может снять (см. importOne)
            importOne(d, sum, regionOverride);
        }
    }

    /** Сеть — ВНЕ транзакции; запись — в отдельной per-item транзакции writer'а. Ошибка элемента не валит прогон. */
    private void importOne(TrdBuyDto d, ImportSummary sum, String regionOverride) {
        try {
            SubjectDto subj = client.fetchSubject(d.effectiveBin());
            List<LotDto> lots = client.fetchLots(d.getNumberAnno());
            List<String> lotTexts = lots.stream()
                    .map(l -> ((l.getNameRu() == null ? "" : l.getNameRu()) + " "
                             + (l.getDescriptionRu() == null ? "" : l.getDescriptionRu())).trim())
                    .toList();
            if (!MedicalRelevanceFilter.isRelevant(d.getNameRu(), lotTexts)) {
                sum.setSkipped(sum.getSkipped() + 1);   // ступень 2: лоты — не медтовар
                sum.setMatched(sum.getMatched() - 1);   // matched считался на ступени-1 (имя) до сети — снять дроп
                return;
            }
            GoszakupTenderWriter.Result r = writer.upsertOne(d, subj, lots, regionOverride);
            if (r == GoszakupTenderWriter.Result.CREATED) sum.setCreated(sum.getCreated() + 1);
            else sum.setUpdated(sum.getUpdated() + 1);
            // non-404 ошибка subject/lots → объявление откладывается до следующего поллинга (учтено в errors, не потеряно)
        } catch (RuntimeException e) {
            sum.setErrors(sum.getErrors() + 1);
            log.warn("goszakup: ошибка импорта объявления {}: {}", d.getNumberAnno(), e.toString());
        }
    }

    private static boolean wholePageOlderThan(List<TrdBuyDto> items, LocalDate cutoff) {
        if (items.isEmpty()) return false;
        for (TrdBuyDto d : items) {
            LocalDate pub = GoszakupParse.localDate(d.getPublishDate());
            if (pub == null || !pub.isBefore(cutoff)) return false; // без даты — консервативно продолжаем
        }
        return true;
    }

    private boolean statusOk(TrdBuyDto d) {
        return statuses.isEmpty() || (d.getRefBuyStatusId() != null && statuses.contains(d.getRefBuyStatusId()));
    }
    /** Только текущий модуль госзакупа (system_id=3); null трактуем как «брать» (поле может отсутствовать). */
    private boolean systemOk(TrdBuyDto d) {
        return d.getSystemId() == null || d.getSystemId() == 3;
    }
    private boolean keywordOk(TrdBuyDto d) {
        String name = d.getNameRu() == null ? "" : d.getNameRu().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(name::contains);
    }
}
