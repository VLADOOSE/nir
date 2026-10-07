package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.LotText;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public GoszakupImportService(GoszakupClient client,
                                 GoszakupTenderWriter writer,
                                 FacilityRepository facilityRepository,
                                 @Value("${goszakup.import.statuses:}") String statusesCsv,
                                 @Value("${goszakup.import.since-days:30}") int sinceDays,
                                 @Value("${goszakup.import.max-pages:60}") int maxPages) {
        this(client, writer, facilityRepository, statusesCsv, sinceDays, maxPages, UpstreamRetry.REAL);
    }

    /** Тесты передают сон-заглушку: паузы повторов (1 и 3 с) не нужны. */
    GoszakupImportService(GoszakupClient client, GoszakupTenderWriter writer, FacilityRepository facilityRepository,
                          String statusesCsv, int sinceDays, int maxPages, UpstreamRetry.Sleeper sleeper) {
        this.sleeper = sleeper;
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
        Map<String, Optional<SubjectDto>> subjects = new HashMap<>();   // subject по БИН — один раз за прогон
        for (Facility org : orgs) {
            sum.setCurrentOrgName(org.getName());
            try {
                fetchOrgFeed(org.getInn(), org.getRegion(), cutoff, sum, subjects);
            } catch (RuntimeException e) {
                sum.addError(org.getName() + ": " + ErrorText.of(e));
                log.warn("goszakup: ошибка импорта по БИН {} ({}): {}", org.getInn(), org.getName(), e.toString());
            }
            sum.setOrgsProcessed(sum.getOrgsProcessed() + 1);
        }
        sum.setMessage(String.format("Больниц %d, получено %d, подходящих %d, создано %d, обновлено %d, ошибок %d",
                sum.getOrgsProcessed(), sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated(), sum.getErrors()));
    }

    private void fetchOrgFeed(String orgBin, String region, LocalDate cutoff, ImportSummary sum,
                              Map<String, Optional<SubjectDto>> subjects) {
        Long after = null;
        int pagesRead = 0;
        do {
            Long a = after;
            var page = UpstreamRetry.call(sleeper, () -> client.fetchTrdBuyPageByOrgBin(orgBin, a));
            List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
            processItems(items, cutoff, sum, region, subjects);
            pagesRead++;
            if (wholePageOlderThan(items, cutoff)) break;
            after = page.getNextAfter();
        } while (after != null && pagesRead < maxPages);
    }

    private void processItems(List<TrdBuyDto> items, LocalDate cutoff, ImportSummary sum, String regionOverride,
                              Map<String, Optional<SubjectDto>> subjects) {
        for (TrdBuyDto d : items) {
            sum.setFetched(sum.getFetched() + 1);
            LocalDate pub = GoszakupParse.localDate(d.getPublishDate());
            if (pub != null && pub.isBefore(cutoff)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            if (!statusOk(d) || !systemOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            importOne(d, sum, regionOverride, subjects);
        }
    }

    /** Сеть — ВНЕ транзакции; запись — в отдельной per-item транзакции writer'а. Ошибка элемента не валит прогон. */
    private void importOne(TrdBuyDto d, ImportSummary sum, String regionOverride,
                           Map<String, Optional<SubjectDto>> subjects) {
        try {
            List<LotDto> lots = UpstreamRetry.call(sleeper, () -> client.fetchLots(d.getNumberAnno()));
            List<LotText> lotTexts = lots.stream()
                    .map(l -> new LotText(l.getNameRu(), l.getDescriptionRu()))
                    .toList();
            if (!MedicalRelevanceFilter.isRelevant(d.getNameRu(), lotTexts)) {
                sum.setSkipped(sum.getSkipped() + 1); // лоты — не медтовар (лекарства/еда/хозтовары/услуги)
                return;
            }
            SubjectDto subj = subjectOf(d.effectiveBin(), subjects);
            GoszakupTenderWriter.Result r = writer.upsertOne(d, subj, lots, regionOverride);
            if (r == GoszakupTenderWriter.Result.CREATED) sum.addCreated(d.getNumberAnno());
            else sum.setUpdated(sum.getUpdated() + 1);
            sum.setMatched(sum.getMatched() + 1); // «подходящих» = медтоварные (созданные + обновлённые)
        } catch (RuntimeException e) {
            sum.addError("объявление " + d.getNumberAnno() + ": " + ErrorText.of(e));
            log.warn("goszakup: ошибка импорта объявления {}: {}", d.getNumberAnno(), e.toString());
        }
    }

    /**
     * subject — после фильтра (≈ 95 % объявлений не профильные) и один раз на БИН за прогон; сбой не роняет
     * тендер: пишем без заказчика (у существующего тендера writer поля заказчика не трогает). Неудача тоже
     * кешируется — иначе каждое объявление той же больницы повторило бы три попытки.
     */
    private SubjectDto subjectOf(String bin, Map<String, Optional<SubjectDto>> cache) {
        if (bin == null || bin.isBlank()) return null;
        return cache.computeIfAbsent(bin, b -> {
            try {
                return Optional.ofNullable(UpstreamRetry.call(sleeper, () -> client.fetchSubject(b)));
            } catch (RuntimeException e) {
                log.warn("goszakup: subject {} недоступен, тендер пишем без него: {}", b, ErrorText.of(e));
                return Optional.empty();
            }
        }).orElse(null);
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
