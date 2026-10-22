package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class GoszakupResultsHttpClient implements GoszakupResultsClient {

    private final GoszakupHttpClient http;

    @Autowired
    public GoszakupResultsHttpClient(ObjectMapper objectMapper,
                                     @Value("${goszakup.api.base-url:https://ows.goszakup.gov.kz/v2}") String baseUrl,
                                     @Value("${goszakup.api.token:}") String token) {
        this(new GoszakupHttpClient(objectMapper, baseUrl, token, 50));
    }

    public GoszakupResultsHttpClient(GoszakupHttpClient http) {
        this.http = http;
    }

    @Override public boolean isConfigured() { return http.isConfigured(); }
    @Override public List<ResultBuy> announcements(List<Long> buyIds) { return http.resultAnnouncements(buyIds); }
    @Override public List<ContractRow> contractsByBuyIds(List<Long> buyIds) { return http.contractsByBuyIds(buyIds); }
    @Override public List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to) {
        return http.contractsByCustomer(bin, from, to);
    }
    @Override public List<AttemptLot> lotsByPlanPoints(List<Long> pointIds) { return http.lotsByPlanPoints(pointIds); }
    @Override public SearchPage<ClosedLot> closedLots(String word, LocalDate from, LocalDate to, Long after) {
        return http.closedLots(word, from, to, after);
    }
    @Override public List<ClosedLot> lotsByIds(List<Long> lotIds) { return http.lotsByIds(lotIds); }
    @Override public byte[] downloadProtocol(String url) { return http.downloadProtocol(url); }

    @Override
    public SubjectInfo subject(String bin) {
        SubjectDto s = http.fetchSubject(bin);
        return s == null ? null : new SubjectInfo(bin, s.getNameRu(), s.getPhone(), s.getEmail());
    }
}
