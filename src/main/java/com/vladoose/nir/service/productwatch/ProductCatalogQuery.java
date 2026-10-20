package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ProductCatalogResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import org.springframework.beans.factory.annotation.Value;
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

    @Transactional(readOnly = true)
    public ProductCatalogResponse list() {
        if (MarketContext.get() != Market.KZ) return new ProductCatalogResponse(false, null, List.of());
        Map<String, ProductRegistration> regs = new HashMap<>();
        registrations.findAll().forEach(r -> regs.put(r.getRegNumber(), r));
        List<ProductCatalogResponse.Item> items = products.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .sorted(Comparator.comparing((SiteProduct p) -> Objects.toString(p.getBrand(), "")).thenComparing(SiteProduct::getName))
                .map(p -> {
                    String reg = RegNumbers.normalize(p.getRegistrationCert());
                    ProductRegistration r = reg == null ? null : regs.get(reg);
                    RegStatus st = reg == null ? RegStatus.NOT_SPECIFIED : r == null ? RegStatus.UNKNOWN : r.getStatus();
                    return new ProductCatalogResponse.Item(p.getId(), p.getName(), p.getBrand(),
                            p.getSlug() == null ? null : WestmedLeadMapper.productUrl(siteUrl, p.getSlug()), reg, st.name(),
                            r == null ? null : r.getExpirationDate(), r != null && Boolean.TRUE.equals(r.getUnlimited()),
                            p.getAttributes() == null ? 0 : p.getAttributes().size());
                }).toList();
        return new ProductCatalogResponse(true, registrations.lastChecked(), items);
    }
}
