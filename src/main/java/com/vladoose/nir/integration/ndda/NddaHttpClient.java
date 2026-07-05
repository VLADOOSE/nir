package com.vladoose.nir.integration.ndda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.integration.ndda.dto.NddaListItemDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP-клиент реестра НЦЭЛС. В отличие от goszakup-клиента сразу кидает UpstreamException
 * (ошибки сети/не-200/кривой JSON) — вызывающему сервису оборачивать нечего.
 */
@Component
public class NddaHttpClient implements NddaClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public NddaHttpClient(ObjectMapper objectMapper,
                          @Value("${ndda.api.base-url:https://oldregister.ndda.kz/register-backend}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Long resolveId(String regNumber) {
        // list с фильтром по точному № РУ → массив из 0/1 элемента (живая форма 2026-07-05)
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("regTypeId", 2);   // тип «МИ»
        filter.put("regPeriod", 1);   // действующие
        filter.put("regNumber", regNumber);
        String body;
        try {
            body = objectMapper.writeValueAsString(filter);
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: не собрался запрос list: " + e.getMessage(), e);
        }
        String json = send(HttpRequest.newBuilder(URI.create(baseUrl + "/RegisterService/list"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30)).build());
        try {
            List<NddaListItemDto> items = objectMapper.readValue(json, new TypeReference<List<NddaListItemDto>>() {});
            return items.isEmpty() ? null : items.get(0).getId();
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: неожиданный ответ list: " + e.getMessage(), e);
        }
    }

    @Override
    public NddaDetailDto fetchDetail(long id) {
        String json = send(HttpRequest.newBuilder(URI.create(baseUrl + "/RegisterService/MtMainGetById?Id=" + id))
                .GET().timeout(Duration.ofSeconds(30)).build());
        try {
            return objectMapper.readValue(json, NddaDetailDto.class);
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: неожиданный ответ карточки: " + e.getMessage(), e);
        }
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new UpstreamException("НЦЭЛС ответил " + resp.statusCode());
            }
            return resp.body();
        } catch (UpstreamException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("НЦЭЛС: запрос прерван", e);
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС недоступен: " + e.getMessage(), e);
        }
    }
}
