package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.integration.http.UpstreamHttp;
import com.vladoose.nir.integration.http.UpstreamRetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP-доступ к ТЗ-файлам fms.ecc.kz. Браузерный UA (портал режет ботов). Токен не нужен.
 * documents-tab → docReqId → actionAjaxModalShowFiles → per-lot PDF (см. SkTechSpecHtmlParser).
 * Дедлайн — на весь ответ (HTML 60 с, PDF 120 с), PDF — не больше {@link #MAX_PDF_BYTES}.
 */
@Component
public class SkTechSpecHttpClient implements SkTechSpecClient {

    static final long MAX_PDF_BYTES = 30L * 1024 * 1024;
    static final Duration PDF_DEADLINE = Duration.ofSeconds(120);

    private final HttpClient http = UpstreamHttp.newClient(HttpClient.Redirect.NORMAL);
    private final String baseUrl;
    private final Duration htmlDeadline;
    private final Duration pdfDeadline;
    private final long maxPdfBytes;

    @Autowired
    public SkTechSpecHttpClient(@Value("${skpharmacy.base-url:https://fms.ecc.kz}") String baseUrl) {
        this(baseUrl, SkPharmacyHttpClient.HTML_DEADLINE, PDF_DEADLINE);
    }

    SkTechSpecHttpClient(String baseUrl, Duration htmlDeadline, Duration pdfDeadline) {
        this(baseUrl, htmlDeadline, pdfDeadline, MAX_PDF_BYTES);
    }

    SkTechSpecHttpClient(String baseUrl, Duration htmlDeadline, Duration pdfDeadline, long maxPdfBytes) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.htmlDeadline = htmlDeadline;
        this.pdfDeadline = pdfDeadline;
        this.maxPdfBytes = maxPdfBytes;
    }

    @Override
    public List<SkTechSpecRef> fetchTechSpecRefs(String announceId) {
        String docsHtml = SkPharmacyHttpClient.getHtml(http,
                baseUrl + "/ru/announce/index/" + announceId + "?tab=documents", htmlDeadline);
        String docReqId = SkTechSpecHtmlParser.parseTechSpecDocReqId(docsHtml);
        if (docReqId == null) return List.of();                       // на объявлении нет требования «ТЗ»
        String modalHtml = SkPharmacyHttpClient.getHtml(http,
                baseUrl + "/ru/announce/actionAjaxModalShowFiles/" + announceId + "/" + docReqId, htmlDeadline);
        return SkTechSpecHtmlParser.parseModal(modalHtml);
    }

    @Override
    public byte[] downloadFile(String url) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", SkPharmacyHttpClient.UA)
                    .timeout(pdfDeadline)
                    .GET().build();
        } catch (IllegalArgumentException e) {
            throw new SkCallException("fms.ecc.kz: неверный адрес файла ТЗ", false, e);
        }
        HttpResponse<byte[]> resp = SkPharmacyHttpClient.exchange(http, req, maxPdfBytes, pdfDeadline);
        if (resp.statusCode() == 404) return null;                    // файл удалён/протух
        if (resp.statusCode() != 200) {
            throw new SkCallException("fms.ecc.kz вернул " + resp.statusCode() + " при скачивании ТЗ",
                    UpstreamRetry.retryableStatus(resp.statusCode()));
        }
        return resp.body();
    }

    @Override
    public boolean isConfigured() { return true; }
}
