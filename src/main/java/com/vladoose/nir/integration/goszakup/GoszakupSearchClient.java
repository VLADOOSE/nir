package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Поиск по стране на v3 GraphQL goszakup (спека 2026-10-19-product-watch §3): лоты по одному слову, объявления по слову
 * в названии (commonName — nameRu ищет только точное название), объявление по номеру, планы, договоры. null в фильтре —
 * фильтр не ставится. Реализует {@link GoszakupHttpClient} тем же токеном.
 */
public interface GoszakupSearchClient {
    boolean isConfigured();
    SearchPage<FoundLot> searchLots(String word, List<Integer> lotStatuses, LocalDate updatedFrom, LocalDate updatedTo, Long after);
    SearchPage<FoundAnnouncement> searchAnnouncements(String word, List<Integer> buyStatuses, LocalDate publishedFrom, LocalDate publishedTo, Long after);
    FoundAnnouncement announcement(String numberAnno);
    List<FoundLot> lotsOfAnnouncement(String numberAnno);
    SearchPage<FoundPlanPoint> searchPlans(String word, List<Integer> years, List<Integer> statuses, Long after);
    List<FoundPlanPoint> plansById(List<Long> ids);
    List<ContractInfo> contracts(String numberAnno);
}
