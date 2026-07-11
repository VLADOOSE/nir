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

/** HTTP-доступ к fms.ecc.kz. Браузерный User-Agent (портал режет ботов без него). */
@Component
public class SkPharmacyHttpClient implements SkPharmacyClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String baseUrl;

    public SkPharmacyHttpClient(@Value("${skpharmacy.base-url:https://fms.ecc.kz}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String searchPage(int page) {
        return get(baseUrl + "/ru/searchanno" + (page > 1 ? "?page=" + page : ""));
    }

    @Override
    public String lotsPage(String announceId) {
        return get(baseUrl + "/ru/announce/index/" + announceId + "?tab=lots");
    }

    private String get(String url) {
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
