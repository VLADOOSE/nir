package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class GoszakupHttpClient implements GoszakupClient {

    private static final Logger log = LoggerFactory.getLogger(GoszakupHttpClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String token;
    private final int pageSize;

    public GoszakupHttpClient(ObjectMapper objectMapper,
                              @Value("${goszakup.api.base-url:https://ows.goszakup.gov.kz/v2}") String baseUrl,
                              @Value("${goszakup.api.token:}") String token,
                              @Value("${goszakup.api.page-size:50}") int pageSize) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.pageSize = pageSize;
    }

    @Override public boolean isConfigured() { return token != null && !token.isBlank(); }

    @Override
    public TrdBuyPageDto fetchTrdBuyPage(String cursor) {
        // cursor — это путь next_page ("/v2/trd-buy?page=next&search_after=...") либо null
        String url = (cursor != null && !cursor.isBlank())
                ? origin() + cursor
                : baseUrl + "/trd-buy?limit=" + pageSize;
        return get(url, TrdBuyPageDto.class);
    }

    @Override
    public List<LotDto> fetchLots(String numberAnno) {
        // лоты приходят в обёртке-странице {items:[...]}
        TypeRefPage<LotDto> page = get(baseUrl + "/lots/number-anno/" + enc(numberAnno),
                new TypeReference<TypeRefPage<LotDto>>() {});
        return page != null && page.items != null ? page.items : List.of();
    }

    @Override
    public SubjectDto fetchSubject(String bin) {
        if (bin == null || bin.isBlank()) return null;
        try {
            return get(baseUrl + "/subject/" + enc(bin), SubjectDto.class);
        } catch (GoszakupNotFoundException notFound) {
            return null; // организации нет в реестре — регион просто не определится
        }
    }

    // --- helpers ---
    /** Тонкая обёртка-страница для эндпоинтов, отдающих {items:[...]}. */
    static class TypeRefPage<T> { public List<T> items; }

    private String origin() {
        // baseUrl="https://ows.goszakup.gov.kz/v2" → origin="https://ows.goszakup.gov.kz"
        int i = baseUrl.indexOf("/", baseUrl.indexOf("://") + 3);
        return i > 0 ? baseUrl.substring(0, i) : baseUrl;
    }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    private <T> T get(String url, Class<T> type) {
        return parse(rawGet(url), b -> objectMapper.readValue(b, type));
    }
    private <T> T get(String url, TypeReference<T> type) {
        return parse(rawGet(url), b -> objectMapper.readValue(b, type));
    }
    private byte[] rawGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) {
                throw new GoszakupNotFoundException("goszakup 404 на " + url);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("goszakup API " + resp.statusCode() + " на " + url);
            }
            return resp.body();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("goszakup API недоступно: " + e.getMessage(), e);
        }
    }
    private interface Parser<T> { T apply(byte[] b) throws java.io.IOException; }
    private <T> T parse(byte[] body, Parser<T> p) {
        try { return p.apply(body); }
        catch (java.io.IOException e) { throw new IllegalStateException("goszakup: разбор JSON: " + e.getMessage(), e); }
    }
}
