package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.dto.response.LotProductMatchDto;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Как показать модель в работе: источник, ссылка на товар сайта, РУ (спека 2026-10-20-lot-model-in-work §5.5, §6). */
@Component
public class LotModelViews {

    private final String siteUrl;
    private final CatalogSource catalog;

    public LotModelViews(@Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl, CatalogSource catalog) {
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
        this.catalog = catalog;
    }

    public LotModelFitResponse.Model model(MedEquipment e) {
        SiteProduct p = e.getSiteProduct();
        MedRegistry r = e.getRegistration();
        String source = p != null ? "SITE" : r != null ? "REGISTRY" : "CATALOG";
        ProductCard c = p == null || catalog == null ? null : catalog.get().card(p.getId());
        String status;
        LocalDate expires;
        boolean unlimited;
        if (c != null) {
            status = c.regStatus().name();
            expires = c.regExpires();
            unlimited = c.regUnlimited();
        } else if (r != null) {
            unlimited = Boolean.TRUE.equals(r.getUnlimited());
            expires = r.getExpirationDate();
            status = unlimited ? "ACTIVE" : expires == null ? "UNKNOWN"
                    : expires.isBefore(LocalDate.now(Market.KZ.zone())) ? "EXPIRED" : "ACTIVE";
        } else {
            status = p != null ? "UNKNOWN" : "NOT_SPECIFIED";
            expires = null;
            unlimited = false;
        }
        return new LotModelFitResponse.Model(e.getId(), e.getName(), e.getManufact(), source, p == null ? null : p.getId(),
                p == null ? null : url(p.getSlug()), r == null ? null : r.getRegNumber(), status, expires, unlimited);
    }

    public LotProductMatchDto.Candidate candidate(FitCandidate c) {
        return new LotProductMatchDto.Candidate(c.productId(), c.name(), url(c.slug()), c.verdict() == null ? null : c.verdict().name(),
                c.met(), c.failed(), c.total(), c.unknown(), c.reason(), c.reasonShort(), c.regStatus(), c.regNumber(),
                c.rows() == null ? List.of() : c.rows().stream()
                        .map(x -> new LotProductMatchDto.Row(x.mark() == null ? null : x.mark().name(), x.text(), x.ours(), x.source()))
                        .toList());
    }

    /** Ссылка на товар сайта и РУ модели в ответе лота. */
    public TenderLotResponse enrich(TenderLotResponse r, TenderLot lot) {
        if (r != null && r.getProposedEquipment() != null && lot != null && lot.getProposedEquipment() != null) {
            LotModelFitResponse.Model m = model(lot.getProposedEquipment());
            r.getProposedEquipment().setProductUrl(m.url());
            r.getProposedEquipment().setRegActive("ACTIVE".equals(m.regStatus()));
            r.getProposedEquipment().setRegStatus(m.regStatus());
            r.getProposedEquipment().setRegExpires(m.regExpires());
        }
        return r;
    }

    private String url(String slug) {
        return slug == null || slug.isBlank() ? null : WestmedLeadMapper.productUrl(siteUrl, slug);
    }
}
