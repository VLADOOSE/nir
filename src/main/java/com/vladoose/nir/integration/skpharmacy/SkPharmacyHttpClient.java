package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.integration.http.UpstreamHttp;
import com.vladoose.nir.integration.http.UpstreamIoException;
import com.vladoose.nir.integration.http.UpstreamRetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP-доступ к fms.ecc.kz. Браузерный User-Agent (портал режет ботов без него). Дедлайн — на ВЕСЬ ответ,
 * тело — не больше {@link #MAX_HTML_BYTES} ({@link UpstreamHttp}); редиректы портала (тот же хост, другой путь)
 * проходим. Сбой — {@link SkCallException} с признаком повтора.
 */
@Component
public class SkPharmacyHttpClient implements SkPharmacyClient {

    static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    /** Предел HTML-страницы портала (реальные — сотни КБ). */
    static final long MAX_HTML_BYTES = 10L * 1024 * 1024;
    static final Duration HTML_DEADLINE = Duration.ofSeconds(60);

    private final HttpClient http = UpstreamHttp.newClient(HttpClient.Redirect.NORMAL);
    private final String baseUrl;
    private final Duration deadline;

    @Autowired
    public SkPharmacyHttpClient(@Value("${skpharmacy.base-url:https://fms.ecc.kz}") String baseUrl) {
        this(baseUrl, HTML_DEADLINE);
    }

    SkPharmacyHttpClient(String baseUrl, Duration deadline) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.deadline = deadline;
    }

    @Override
    public String searchPage(int page) {
        return getHtml(http, baseUrl + "/ru/searchanno" + (page > 1 ? "?page=" + page : ""), deadline);
    }

    @Override
    public String lotsPage(String announceId, int page) {
        return getHtml(http, baseUrl + "/ru/announce/index/" + announceId + "?tab=lots" + (page > 1 ? "&page=" + page : ""),
                deadline);
    }

    @Override
    public String generalPage(String announceId) {
        return getHtml(http, baseUrl + "/ru/announce/index/" + announceId + "?tab=general", deadline);
    }

    /** Общий GET HTML-страницы портала (и для {@link SkTechSpecHttpClient}). */
    static String getHtml(HttpClient http, String url, Duration deadline) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ru,en")
                    .timeout(deadline)
                    .GET().build();
        } catch (IllegalArgumentException e) {
            throw new SkCallException("fms.ecc.kz: неверный адрес запроса", false, e);
        }
        HttpResponse<byte[]> resp = exchange(http, req, MAX_HTML_BYTES, deadline);
        checkStatus(resp.statusCode(), url);
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    static HttpResponse<byte[]> exchange(HttpClient http, HttpRequest req, long maxBytes, Duration deadline) {
        try {
            return UpstreamHttp.exchange(http, req, maxBytes, deadline);
        } catch (UpstreamIoException e) {
            if (e.kind() == UpstreamIoException.Kind.INTERRUPTED) {
                throw new SkCallException("Прервано при запросе к fms.ecc.kz", false, e);
            }
            throw new SkCallException("Сеть fms.ecc.kz: " + e.getMessage(), e.retryable(), e);
        }
    }

    static void checkStatus(int code, String url) {
        if (code != 200) {
            throw new SkCallException("fms.ecc.kz вернул " + code + " для " + URI.create(url).getPath(),
                    UpstreamRetry.retryableStatus(code));
        }
    }
}
