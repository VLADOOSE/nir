package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.WestmedCatalogClient;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Каталог westmed.kz → site_product (спека 2026-10-19-product-watch §6). Сеть — вне транзакции, запись — одной
 * транзакцией; пустой ответ сайта — сбой, а не «все товары пропали» (иначе разовая заглушка сайта сняла бы каталог).
 */
@Service
public class SiteCatalogSync {

    private static final Logger log = LoggerFactory.getLogger(SiteCatalogSync.class);

    public record SyncResult(int count, int added, int updated, int removed, boolean full) {}

    private final WestmedCatalogClient client;
    private final SiteProductRepository products;
    private final ProductCatalog catalog;
    private TransactionTemplate tx;

    public SiteCatalogSync(WestmedCatalogClient client, SiteProductRepository products, ProductCatalog catalog) {
        this.client = client;
        this.products = products;
        this.catalog = catalog;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(PlatformTransactionManager tm) { this.tx = new TransactionTemplate(tm); }

    public SyncResult sync() {
        boolean full = client.adminConfigured();
        List<WestmedCatalogItem> items;
        try {
            items = full ? client.adminCatalog() : client.publicCatalog();
        } catch (RuntimeException e) {
            log.warn("Каталог сайта не загрузился: {}", ErrorText.of(e));
            throw new UpstreamException("Каталог сайта не загрузился: " + ErrorText.of(e), e);
        }
        if (items.isEmpty()) throw new UpstreamException("Каталог сайта не загрузился: сайт отдал пустой список", null);
        SyncResult r = tx == null ? write(items, full) : tx.execute(s -> write(items, full));
        catalog.refresh();
        log.info("Каталог сайта: {} товаров (новых {}, изменено {}, пропало {}){}", r.count(), r.added(), r.updated(),
                r.removed(), full ? "" : " — публичный список, без описаний");
        return r;
    }

    private SyncResult write(List<WestmedCatalogItem> items, boolean full) {
        Market market = MarketContext.get();
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, SiteProduct> known = new HashMap<>();
        for (SiteProduct p : products.findByMarket(market)) known.put(p.getSiteId(), p);
        Set<UUID> seen = new HashSet<>();
        int added = 0, updated = 0, removed = 0;
        for (WestmedCatalogItem i : items) {
            if (!seen.add(i.id())) continue;
            SiteProduct p = known.get(i.id());
            boolean isNew = p == null;
            if (isNew) p = SiteProduct.builder().market(market).siteId(i.id()).build();
            boolean changed = isNew || !Objects.equals(p.getName(), i.name()) || p.getRemovedAt() != null
                    || (full && !Objects.equals(p.getDescription(), i.description()));
            p.setName(trunc(i.name(), 500));
            p.setSku(trunc(i.sku(), 50));
            p.setSlug(trunc(i.slug(), 500));
            p.setBrand(trunc(i.brand(), 200));
            p.setCategory(trunc(i.category(), 200));
            p.setShortDescription(i.shortDescription());
            if (full) {                                  // публичный список описаний не несёт — прежние не затираем
                p.setDescription(i.description());
                p.setAttributes(i.attributes() == null ? new ArrayList<>() : new ArrayList<>(i.attributes()));
                p.setRegistrationCert(trunc(i.registrationCert(), 200));
                p.setSiteUpdatedAt(i.updatedAt() == null ? null : i.updatedAt().atOffset(ZoneOffset.UTC));
            }
            p.setRemovedAt(null);
            p.setSyncedAt(now);
            products.save(p);
            if (isNew) added++; else if (changed) updated++;
        }
        for (SiteProduct p : known.values()) {
            if (!seen.contains(p.getSiteId()) && p.getRemovedAt() == null) {
                p.setRemovedAt(now);
                products.save(p);
                removed++;
            }
        }
        return new SyncResult(seen.size(), added, updated, removed, full);
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
