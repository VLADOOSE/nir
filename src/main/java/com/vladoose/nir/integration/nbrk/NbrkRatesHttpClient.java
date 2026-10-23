package com.vladoose.nir.integration.nbrk;

import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.http.UpstreamHttp;
import com.vladoose.nir.integration.http.UpstreamIoException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Официальные курсы НБ РК без ключа (спека §3): `GET {base}/rss/get_rates.cfm?fdate=ДД.ММ.ГГГГ`; дедлайн на весь ответ. */
@Component
public class NbrkRatesHttpClient implements NbrkRatesClient {

    static final Duration DEADLINE = Duration.ofSeconds(20);
    static final long MAX_BYTES = 1024 * 1024;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final String baseUrl;
    private final HttpClient http;
    private final Duration deadline;

    @Autowired
    public NbrkRatesHttpClient(@Value("${rates.nbrk.base-url:https://nationalbank.kz}") String baseUrl) {
        this(baseUrl, UpstreamHttp.newClient(HttpClient.Redirect.NORMAL), DEADLINE);
    }

    public NbrkRatesHttpClient(String baseUrl, HttpClient http, Duration deadline) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.http = http;
        this.deadline = deadline;
    }

    @Override
    public List<NbrkRate> fetch(LocalDate date) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(baseUrl + "/rss/get_rates.cfm?fdate=" + date.format(DATE)))
                    .header("Accept", "application/xml, text/xml").GET().build();
        } catch (IllegalArgumentException e) {
            throw new UpstreamException("Нацбанк РК: адрес не собирается — проверьте RATES_NBRK_BASE_URL");
        }
        HttpResponse<byte[]> r;
        try {
            r = UpstreamHttp.exchange(http, req, MAX_BYTES, deadline);
        } catch (UpstreamIoException e) {
            throw new UpstreamException("Нацбанк РК: " + e.getMessage());
        }
        if (r.statusCode() != 200) throw new UpstreamException("Нацбанк РК: HTTP " + r.statusCode());
        return NbrkRatesParser.parse(r.body());
    }
}
