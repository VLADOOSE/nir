package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotModelSearchResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.productwatch.CatalogSnapshot;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.ProductText;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Поиск «модели в работе» (спека 2026-10-20-lot-model-in-work §5.4): наши товары сайта и записи реестра НЦЭЛС. */
@Service
public class LotModelSearch {

    public static final int LIMIT = 10;
    private static final int MAX_QUERY = 200;

    private final LotModelService lotModel;
    private final SiteProductRepository siteProducts;
    private final MedRegistryRepository registry;
    private final RegistryMatchService registryMatch;
    private final CatalogSource catalog;
    private final String siteUrl;

    public LotModelSearch(LotModelService lotModel, SiteProductRepository siteProducts, MedRegistryRepository registry,
                          RegistryMatchService registryMatch, CatalogSource catalog,
                          @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.lotModel = lotModel;
        this.siteProducts = siteProducts;
        this.registry = registry;
        this.registryMatch = registryMatch;
        this.catalog = catalog;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public LotModelSearchResponse search(Long lotId, String q) {
        lotModel.lotInMarket(lotId);
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        String query = q == null ? "" : q.strip();
        if (query.length() < 2) return new LotModelSearchResponse(List.of(), List.of());
        if (query.length() > MAX_QUERY) query = query.substring(0, MAX_QUERY);
        return new LotModelSearchResponse(ours(query), registry(query));
    }

    /** Наши товары: каждое слово запроса — в названии, бренде или артикуле; снятые с сайта не ищутся. */
    private List<LotModelSearchResponse.Our> ours(String q) {
        List<String> words = ProductText.words(ProductText.normalize(q));
        if (words.isEmpty()) return List.of();
        CatalogSnapshot snap = catalog.get();
        return siteProducts.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .filter(p -> {
                    String hay = ProductText.normalize(String.join(" ", nz(p.getName()), nz(p.getBrand()), nz(p.getSku())));
                    return words.stream().allMatch(hay::contains);
                })
                .sorted(Comparator.comparing((SiteProduct p) -> nz(p.getName()), String.CASE_INSENSITIVE_ORDER))
                .limit(LIMIT)
                .map(p -> {
                    ProductCard c = snap.card(p.getId());
                    return new LotModelSearchResponse.Our(p.getId(), p.getName(), p.getBrand(), p.getSku(),
                            RegNumbers.normalize(p.getRegistrationCert()), c == null ? RegStatus.UNKNOWN.name() : c.regStatus().name(),
                            c == null ? null : c.regExpires(),
                            p.getSlug() == null || p.getSlug().isBlank() ? null : WestmedLeadMapper.productUrl(siteUrl, p.getSlug()));
                }).toList();
    }

    /** Реестр: номер РУ в запросе (есть цифры) — точная запись первой; дальше — поиск по словам «Подбора». */
    private List<LotModelSearchResponse.Registry> registry(String q) {
        LocalDate today = LocalDate.now(Market.KZ.zone());
        List<LotModelSearchResponse.Registry> out = new ArrayList<>();
        if (q.chars().anyMatch(Character::isDigit)) {
            registry.findByNormalizedRegNumber(RegNumbers.normalize(q)).ifPresent(r -> out.add(row(r, today)));
        }
        for (RegistryCandidateResponse c : registryMatch.search(q, LIMIT)) {
            if (out.size() >= LIMIT) break;
            if (out.stream().anyMatch(r -> r.regNumber().equals(c.getRegNumber()))) continue;
            out.add(new LotModelSearchResponse.Registry(c.getRegNumber(), c.getName(), c.getProducer(), c.getCountry(),
                    c.getExpirationDate(), Boolean.TRUE.equals(c.getUnlimited()), expired(c.getUnlimited(), c.getExpirationDate(), today)));
        }
        return out;
    }

    private static LotModelSearchResponse.Registry row(MedRegistry r, LocalDate today) {
        return new LotModelSearchResponse.Registry(r.getRegNumber(), r.getName(), r.getProducer(), r.getCountry(), r.getExpirationDate(),
                Boolean.TRUE.equals(r.getUnlimited()), expired(r.getUnlimited(), r.getExpirationDate(), today));
    }

    static boolean expired(Boolean unlimited, LocalDate expires, LocalDate today) {
        return !Boolean.TRUE.equals(unlimited) && expires != null && expires.isBefore(today);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
