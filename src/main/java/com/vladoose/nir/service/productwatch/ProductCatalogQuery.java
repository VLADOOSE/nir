package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.ProductCatalogResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.service.pricelist.PriceHints;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Товары каталога с РУ — вкладка «Товары» (спека §8.2); на РФ — пусто. */
@Service
public class ProductCatalogQuery {

    private final SiteProductRepository products;
    private final ProductRegistrationRepository registrations;
    private final String siteUrl;

    public ProductCatalogQuery(SiteProductRepository products, ProductRegistrationRepository registrations,
                               @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.products = products;
        this.registrations = registrations;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    private PriceHints priceHints;

    /** Подсказки по прайсам у товаров; нет бина (тесты, собранные руками) — без подсказок. */
    @Autowired(required = false)
    public void setPriceHints(PriceHints priceHints) {
        this.priceHints = priceHints;
    }

    @Transactional(readOnly = true)
    public ProductCatalogResponse list() {
        if (MarketContext.get() != Market.KZ) return new ProductCatalogResponse(false, null, List.of());
        Map<String, ProductRegistration> regs = new HashMap<>();
        registrations.findAll().forEach(r -> regs.put(r.getRegNumber(), r));
        List<SiteProduct> list = products.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .sorted(Comparator.comparing((SiteProduct p) -> Objects.toString(p.getBrand(), "")).thenComparing(SiteProduct::getName))
                .toList();
        // «📒 по прайсу» у товара (спека 2026-10-23 §6.2 п. 5) — одним запросом строк; завод бренда товара — первым
        List<PriceHint> prices = priceHints == null ? null : priceHints.hints(Market.KZ,
                list.stream().map(p -> new PriceHints.Target(null, p.getId(), null, p.getBrand())).toList());
        List<ProductCatalogResponse.Item> items = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            SiteProduct p = list.get(i);
            String reg = RegNumbers.normalize(p.getRegistrationCert());
            ProductRegistration r = reg == null ? null : regs.get(reg);
            RegStatus st = reg == null ? RegStatus.NOT_SPECIFIED : r == null ? RegStatus.UNKNOWN : r.getStatus();
            items.add(new ProductCatalogResponse.Item(p.getId(), p.getName(), p.getBrand(),
                    p.getSlug() == null ? null : WestmedLeadMapper.productUrl(siteUrl, p.getSlug()), reg, st.name(),
                    r == null ? null : r.getExpirationDate(), r != null && Boolean.TRUE.equals(r.getUnlimited()),
                    p.getAttributes() == null ? 0 : p.getAttributes().size(), prices == null ? null : prices.get(i)));
        }
        return new ProductCatalogResponse(true, registrations.lastChecked(), items);
    }
}
