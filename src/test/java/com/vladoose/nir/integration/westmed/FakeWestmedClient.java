package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;

import java.util.*;

/** Управляемый фейк API сайта: заявки (новые сверху, как отдаёт сайт), каталог, запись статусов. Без сети. */
public class FakeWestmedClient implements WestmedClient {

    public boolean configured = true;
    public final List<WestmedPriceRequest> priceRequests = new ArrayList<>();
    public final List<WestmedQuoteRequest> quoteRequests = new ArrayList<>();
    /** ключ — текст поиска в нижнем регистре */
    public final Map<String, List<WestmedProduct>> productsBySearch = new HashMap<>();
    public final List<Integer> pricePages = new ArrayList<>();
    public final List<Integer> quotePages = new ArrayList<>();
    public final List<String> statusUpdates = new ArrayList<>();
    public final Set<String> missingOnSite = new HashSet<>();
    public RuntimeException failFetchWith;
    public RuntimeException failUpdatesWith;
    public RuntimeException failSearchWith;
    public int searchCalls;

    @Override
    public boolean isConfigured() { return configured; }

    @Override
    public WestmedPage<WestmedPriceRequest> fetchPriceRequests(int page, int size) {
        if (failFetchWith != null) throw failFetchWith;
        pricePages.add(page);
        return page(priceRequests, page, size);
    }

    @Override
    public WestmedPage<WestmedQuoteRequest> fetchQuoteRequests(int page, int size) {
        if (failFetchWith != null) throw failFetchWith;
        quotePages.add(page);
        return page(quoteRequests, page, size);
    }

    @Override
    public List<WestmedProduct> searchProducts(String text, int size) {
        searchCalls++;
        if (failSearchWith != null) throw failSearchWith;
        return productsBySearch.getOrDefault(text.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public void updateStatus(WestmedKind kind, String siteId, String status) {
        if (failUpdatesWith != null) throw failUpdatesWith;
        if (missingOnSite.contains(siteId)) {
            throw new WestmedApiException(404, "HTTP 404 на PATCH /api/admin/" + kind.path() + "/" + siteId + "/status");
        }
        statusUpdates.add(kind + ":" + siteId + "=" + status);
    }

    static <T> WestmedPage<T> page(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = (all.size() + size - 1) / size;
        return new WestmedPage<>(new ArrayList<>(all.subList(from, to)), page >= totalPages - 1, totalPages, page);
    }
}
