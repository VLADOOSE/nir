package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.SiteProductResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.integration.westmed.WestmedApiException;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Без Spring: фейк сайта из тестов интеграции, рынок — через MarketContext. */
class SiteProductServiceTest {

    private final FakeWestmedClient fake = new FakeWestmedClient();
    private final SiteProductService service = new SiteProductService(fake, "https://westmed.kz/");

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void kzSearchMapsProductsWithUrl() {
        MarketContext.set(Market.KZ);
        fake.productsBySearch.put("облучатель", List.of(
                new WestmedProduct("Облучатель ОБН-150", "obn-150", "Азов", "AZ-150"),
                new WestmedProduct("Облучатель без ссылки", null, " ", null),
                new WestmedProduct("  ", "empty", "X", null)));

        List<SiteProductResponse> r = service.search("  Облучатель ");

        assertThat(r).containsExactly(
                new SiteProductResponse("Облучатель ОБН-150", "Азов", "AZ-150", "https://westmed.kz/product/obn-150"),
                new SiteProductResponse("Облучатель без ссылки", null, null, null));
    }

    @Test
    void shortQueryOrRfMarketDoesNotCallSite() {
        MarketContext.set(Market.KZ);
        assertThat(service.search("о")).isEmpty();
        assertThat(service.search(null)).isEmpty();
        MarketContext.set(Market.RF);
        assertThat(service.search("облучатель")).isEmpty();
        assertThat(fake.searchCalls).isZero();
    }

    @Test
    void siteFailureIsUpstreamWithOwnText() {
        MarketContext.set(Market.KZ);
        fake.failSearchWith = new WestmedApiException(0, "сайт не ответил за 5 с");

        assertThatThrownBy(() -> service.search("облучатель"))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Сайт westmed.kz не отвечает — добавьте позицию своим текстом");
    }

    @Test
    void longQueryIsCutTo100() {
        MarketContext.set(Market.KZ);
        String longQ = "а".repeat(150);
        fake.productsBySearch.put("а".repeat(100), List.of(new WestmedProduct("Найдено", "x", null, null)));

        assertThat(service.search(longQ)).extracting(SiteProductResponse::name).containsExactly("Найдено");
    }
}
