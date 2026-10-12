package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.SiteProductResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.WestmedClient;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Поиск по каталогу westmed.kz для позиций частной заявки (спека §4): живой публичный поиск сайта, каталог в АИС не
 * копируется. Только KZ — у Регион-Мед своего сайта нет. Карточку товара /products/{slug} не зовём: она накручивает
 * просмотры (CLAUDE.md §8).
 */
@Service
public class SiteProductService {

    static final int MIN_QUERY = 2;
    static final int MAX_QUERY = 100;
    static final int LIMIT = 10;
    static final Duration DEADLINE = Duration.ofSeconds(5);

    private final WestmedClient client;
    private final String siteUrl;

    public SiteProductService(WestmedClient client, @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.client = client;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    public List<SiteProductResponse> search(String q) {
        if (MarketContext.get() != Market.KZ) return List.of();
        String text = q == null ? "" : q.strip().replaceAll("\\s+", " ");
        if (text.length() < MIN_QUERY) return List.of();
        if (text.length() > MAX_QUERY) text = text.substring(0, MAX_QUERY);
        List<WestmedProduct> found;
        try {
            found = client.searchProducts(text.toLowerCase(Locale.ROOT), LIMIT, DEADLINE);
        } catch (RuntimeException e) {
            throw new UpstreamException("Сайт westmed.kz не отвечает — добавьте позицию своим текстом", e);
        }
        return found.stream()
                .filter(p -> p.name() != null && !p.name().isBlank())
                .limit(LIMIT)
                .map(p -> new SiteProductResponse(p.name().strip(), blankToNull(p.brandName()), blankToNull(p.sku()),
                        blankToNull(p.slug()) == null ? null : siteUrl + "/product/" + p.slug().strip()))
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
