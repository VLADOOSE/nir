package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;

import java.util.List;

/** API сайта westmed.kz — те же вызовы, что у его админки (спека §2). Интерфейс — ради фейка в тестах. */
public interface WestmedClient {

    boolean isConfigured();

    WestmedPage<WestmedPriceRequest> fetchPriceRequests(int page, int size);

    WestmedPage<WestmedQuoteRequest> fetchQuoteRequests(int page, int size);

    /** Публичный поиск каталога (name ILIKE). Карточку товара /products/{slug} НЕ вызываем — она накручивает просмотры. */
    List<WestmedProduct> searchProducts(String text, int size);

    void updateStatus(WestmedKind kind, String siteId, String status);
}
