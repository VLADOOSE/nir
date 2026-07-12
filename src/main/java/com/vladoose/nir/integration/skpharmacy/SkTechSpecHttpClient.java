package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.exception.UpstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * HTTP-доступ к ТЗ-файлам fms.ecc.kz. Браузерный UA (портал режет ботов). Токен не нужен.
 * documents-tab → docReqId → actionAjaxModalShowFiles → per-lot PDF (см. SkTechSpecHtmlParser).
 */
@Component
public class SkTechSpecHttpClient implements SkTechSpecClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String baseUrl;

    public SkTechSpecHttpClient(@Value("${skpharmacy.base-url:https://fms.ecc.kz}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public List<SkTechSpecRef> fetchTechSpecRefs(String announceId) {
        String docsHtml = getHtml(baseUrl + "/ru/announce/index/" + announceId + "?tab=documents");
        String docReqId = SkTechSpecHtmlParser.parseTechSpecDocReqId(docsHtml);
        if (docReqId == null) return List.of();                       // на объявлении нет требования «ТЗ»
        String modalHtml = getHtml(baseUrl + "/ru/announce/actionAjaxModalShowFiles/" + announceId + "/" + docReqId);
        return SkTechSpecHtmlParser.parseModal(modalHtml);
    }

    @Override
    public byte[] downloadFile(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA)
                    .timeout(Duration.ofSeconds(60))
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) return null;                // файл удалён/протух
            if (resp.statusCode() != 200) {
                throw new UpstreamException("fms.ecc.kz вернул " + resp.statusCode() + " при скачивании ТЗ");
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Прервано при скачивании ТЗ");
        } catch (java.io.IOException e) {
            throw new UpstreamException("Сеть fms.ecc.kz: " + e.getMessage());
        }
    }

    @Override
    public boolean isConfigured() { return true; }

    private String getHtml(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ru,en")
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new UpstreamException("fms.ecc.kz вернул " + resp.statusCode() + " для " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Прервано при запросе к fms.ecc.kz");
        } catch (java.io.IOException e) {
            throw new UpstreamException("Сеть fms.ecc.kz: " + e.getMessage());
        }
    }
}
