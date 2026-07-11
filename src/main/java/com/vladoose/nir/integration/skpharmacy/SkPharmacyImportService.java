package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.integration.goszakup.ImportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public SkPharmacyImportService(SkPharmacyClient client, SkPharmacyTenderWriter writer,
                                   @Value("${skpharmacy.import.max-pages:30}") int maxPages) {
        this.client = client;
        this.writer = writer;
        this.maxPages = maxPages;
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
                try {
                    if (!SkPharmacyRelevanceFilter.nameCandidate(a.nameRu())) {   // ступень 1 — явные лекарства
                        sum.setSkipped(sum.getSkipped() + 1);
                        continue;
                    }
                    List<SkLot> lots = SkPharmacyHtmlParser.parseLots(client.lotsPage(a.announceId()));
                    List<String> lotNames = lots.stream().map(SkLot::name).toList();
                    if (!SkPharmacyRelevanceFilter.isRelevant(a.nameRu(), lotNames)) {   // ступень 2 — по лотам
                        sum.setSkipped(sum.getSkipped() + 1);
                        continue;
                    }
                    sum.setMatched(sum.getMatched() + 1);
                    if (writer.upsert(a, lots) == SkPharmacyTenderWriter.Result.CREATED) {
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
