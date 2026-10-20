package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Поиск по стране на v3 goszakup (спека 2026-10-19-product-watch §3) — отдельным бином со СВОИМ экземпляром
 * {@link GoszakupHttpClient} (тот же адрес и токен). Был бы поиск тем же бином, что {@link GoszakupClient}, — тест с
 * {@code @MockitoBean GoszakupClient} подменял бы бин целиком, и всё, что ищет по стране, не поднималось бы.
 */
@Component
public class GoszakupSearchHttpClient implements GoszakupSearchClient {

    private final GoszakupHttpClient http;

    @Autowired
    public GoszakupSearchHttpClient(ObjectMapper objectMapper,
                                    @Value("${goszakup.api.base-url:https://ows.goszakup.gov.kz/v2}") String baseUrl,
                                    @Value("${goszakup.api.token:}") String token) {
        this(new GoszakupHttpClient(objectMapper, baseUrl, token, 50));
    }

    public GoszakupSearchHttpClient(GoszakupHttpClient http) {
        this.http = http;
    }

    @Override public boolean isConfigured() { return http.isConfigured(); }

    @Override
    public SearchPage<FoundLot> searchLots(String word, List<Integer> lotStatuses, LocalDate updatedFrom, LocalDate updatedTo, Long after) {
        return http.searchLots(word, lotStatuses, updatedFrom, updatedTo, after);
    }

    @Override
    public SearchPage<FoundAnnouncement> searchAnnouncements(String word, List<Integer> buyStatuses, LocalDate publishedFrom,
                                                             LocalDate publishedTo, Long after) {
        return http.searchAnnouncements(word, buyStatuses, publishedFrom, publishedTo, after);
    }

    @Override public FoundAnnouncement announcement(String numberAnno) { return http.announcement(numberAnno); }

    @Override public List<FoundLot> lotsOfAnnouncement(String numberAnno) { return http.lotsOfAnnouncement(numberAnno); }

    @Override
    public SearchPage<FoundPlanPoint> searchPlans(String word, List<Integer> years, List<Integer> statuses, Long after) {
        return http.searchPlans(word, years, statuses, after);
    }

    @Override public List<FoundPlanPoint> plansById(List<Long> ids) { return http.plansById(ids); }

    @Override public List<ContractInfo> contracts(String numberAnno) { return http.contracts(numberAnno); }
}
