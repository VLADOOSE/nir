package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.FacilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Импорт KZ-тендеров goszakup по РЕЕСТРУ больниц: перебирает мониторимые учреждения (facility,
 * рынок KZ, monitor_tenders=true) выбранного региона, по каждому дёргает v3 TrdBuy(orgBin),
 * фетчит лоты и апсертит через GoszakupTenderWriter. «Медтовар» решается по ЛОТАМ, не по имени.
 * Сам НЕ транзакционный: сетевой I/O не держит БД-коннект; ошибка одной больницы/тендера идёт в
 * ImportSummary.errors и не валит прогон.
 */
@Service
public class GoszakupImportService {

    private static final Logger log = LoggerFactory.getLogger(GoszakupImportService.class);

    private final GoszakupClient client;
    private final GoszakupTenderWriter writer;
    private final FacilityRepository facilityRepository;
    private final Set<Integer> statuses;
    private final int sinceDays;
    private final int maxPages;

    public GoszakupImportService(GoszakupClient client,
                                 GoszakupTenderWriter writer,
                                 FacilityRepository facilityRepository,
                                 @Value("${goszakup.import.statuses:}") String statusesCsv,
                                 @Value("${goszakup.import.since-days:30}") int sinceDays,
                                 @Value("${goszakup.import.max-pages:60}") int maxPages) {
        this.client = client;
        this.writer = writer;
        this.facilityRepository = facilityRepository;
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

    /** region — каноническое имя области/города (как в фильтре UI) или null: все мониторимые больницы KZ. */
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
        List<Facility> orgs = (region == null || region.isBlank())
                ? facilityRepository.findByMarketAndMonitorTendersTrue(Market.KZ)
                : facilityRepository.findByMarketAndRegionAndMonitorTendersTrue(Market.KZ, region.trim());
        orgs = orgs.stream().filter(f -> f.getInn() != null && !f.getInn().isBlank()).toList();
        sum.setOrgsTotal(orgs.size());
        if (orgs.isEmpty()) {
            sum.setEnabled(false);
            sum.setMessage(region == null || region.isBlank()
                    ? "В реестре нет учреждений с мониторингом тендеров (KZ)"
                    : "В реестре нет учреждений с мониторингом тендеров для региона: " + region);
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(sinceDays);
        for (Facility org : orgs) {
            sum.setCurrentOrgName(org.getName());
            try {
                fetchOrgFeed(org.getInn(), org.getRegion(), cutoff, sum);
            } catch (RuntimeException e) {
                sum.setErrors(sum.getErrors() + 1);
                log.warn("goszakup: ошибка импорта по БИН {} ({}): {}", org.getInn(), org.getName(), e.toString());
            }
            sum.setOrgsProcessed(sum.getOrgsProcessed() + 1);
        }
        sum.setMessage(String.format("Больниц %d, получено %d, подходящих %d, создано %d, обновлено %d, ошибок %d",
                sum.getOrgsProcessed(), sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated(), sum.getErrors()));
    }

    private void fetchOrgFeed(String orgBin, String region, LocalDate cutoff, ImportSummary sum) {
        Long after = null;
        int pagesRead = 0;
        do {
            var page = client.fetchTrdBuyPageByOrgBin(orgBin, after);
            List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
            processItems(items, cutoff, sum, region);
            pagesRead++;
            if (wholePageOlderThan(items, cutoff)) break;
            after = page.getNextAfter();
        } while (after != null && pagesRead < maxPages);
    }

    private void processItems(List<TrdBuyDto> items, LocalDate cutoff, ImportSummary sum, String regionOverride) {
        for (TrdBuyDto d : items) {
            sum.setFetched(sum.getFetched() + 1);
            LocalDate pub = GoszakupParse.localDate(d.getPublishDate());
            if (pub != null && pub.isBefore(cutoff)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            if (!statusOk(d) || !systemOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
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
                sum.setSkipped(sum.getSkipped() + 1); // лоты — не медтовар (лекарства/еда/хозтовары/услуги)
                return;
            }
            GoszakupTenderWriter.Result r = writer.upsertOne(d, subj, lots, regionOverride);
            if (r == GoszakupTenderWriter.Result.CREATED) sum.setCreated(sum.getCreated() + 1);
            else sum.setUpdated(sum.getUpdated() + 1);
            sum.setMatched(sum.getMatched() + 1); // «подходящих» = медтоварные (созданные + обновлённые)
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
}
