package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class GoszakupImportService {

    private final GoszakupClient client;
    private final RegionResolver regionResolver;
    private final TenderRepository tenderRepository;
    private final List<String> keywords;
    private final java.util.Set<Integer> statuses;
    private final int sinceDays;
    private final int maxPages;

    public GoszakupImportService(GoszakupClient client,
                                 RegionResolver regionResolver,
                                 TenderRepository tenderRepository,
                                 @Value("${goszakup.import.keywords:}") String keywordsCsv,
                                 @Value("${goszakup.import.statuses:}") String statusesCsv,
                                 @Value("${goszakup.import.since-days:30}") int sinceDays,
                                 @Value("${goszakup.import.max-pages:20}") int maxPages) {
        this.client = client;
        this.regionResolver = regionResolver;
        this.tenderRepository = tenderRepository;
        this.keywords = csv(keywordsCsv).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.statuses = csv(statusesCsv).stream().map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
        this.sinceDays = sinceDays;
        this.maxPages = maxPages;
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
    }

    @Transactional
    public ImportSummary importMedicalTenders() {
        ImportSummary sum = new ImportSummary();
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return sum;
        }
        // Task 4: одна страница. Task 5 заменит на цикл по cursor + since-days + max-pages.
        TrdBuyPageDto page = client.fetchTrdBuyPage(null);
        List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
        sum.setFetched(items.size());
        for (TrdBuyDto d : items) {
            if (!statusOk(d) || !keywordOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            sum.setMatched(sum.getMatched() + 1);
            upsert(d, sum);
        }
        sum.setMessage(String.format("Получено %d, релевантных %d, создано %d, обновлено %d",
                sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated()));
        return sum;
    }

    private boolean statusOk(TrdBuyDto d) {
        return statuses.isEmpty() || (d.getRefBuyStatusId() != null && statuses.contains(d.getRefBuyStatusId()));
    }
    private boolean keywordOk(TrdBuyDto d) {
        String name = d.getNameRu() == null ? "" : d.getNameRu().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(name::contains);
    }

    /** Task 4: только create. Task 5 добавит ветку update + лоты + регион. */
    private void upsert(TrdBuyDto d, ImportSummary sum) {
        Tender t = new Tender();
        t.setSourceExtId(d.getNumberAnno());
        applyFields(t, d);
        tenderRepository.save(t);
        sum.setCreated(sum.getCreated() + 1);
    }

    private void applyFields(Tender t, TrdBuyDto d) {
        t.setTenderNumber(d.getNumberAnno());
        t.setSource(Source.PUBLIC_TENDER);
        t.setMarket(Market.KZ);
        t.setCurrency("KZT");
        t.setFacility(null);
        t.setStatus(mapStatus(d.getRefBuyStatusId()));
        t.setDescription(d.getNameRu());
        t.setTotalCost(d.getTotalSum());
        t.setPublishDate(parseDate(d.getPublishDate()));
        t.setDeadline(parseDate(d.getEndDate()));
        t.setCustomerBin(d.getCustomerBin());
    }

    static String mapStatus(Integer refBuyStatusId) {
        // дефолт: импортные тендеры считаем активными (точный маппинг id уточняется на Task 9)
        return "ACTIVE";
    }

    static LocalDate parseDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
