package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import com.vladoose.nir.service.productwatch.fit.ProductCards;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** Модель в работе для сверки — одним чтением (спека 2026-10-20-lot-model-in-work §6). */
@Service
public class LotModelReader {

    private final LotModelService lotModel;
    private final CatalogSource catalog;
    private final LotModelViews views;

    public LotModelReader(LotModelService lotModel, CatalogSource catalog, LotModelViews views) {
        this.lotModel = lotModel;
        this.catalog = catalog;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public ModelSnapshot read(Long lotId) {
        return snapshot(lotModel.lotInMarket(lotId));
    }

    /**
     * Снимок уже загруженного лота — без своей транзакции: «Для ИИ» зовёт его внутри своей и ловит сбой сам (сбой внутри
     * чужого {@code @Transactional}-метода пометил бы её «только откат» и уронил бы весь текст).
     */
    public ModelSnapshot snapshot(TenderLot lot) {
        String text = ProductMatchService.lotFitText(lot);
        MedEquipment eq = lot.getProposedEquipment();
        if (eq == null) return new ModelSnapshot(text, null, false, null, null, null, null, null);
        ProductCard card;
        boolean needsDetail = false;
        String regNumber = eq.getRegistration() == null ? null : eq.getRegistration().getRegNumber();
        if (eq.getSiteProduct() != null) {
            card = catalog.get().card(eq.getSiteProduct().getId());
            if (card == null) card = ProductCards.of(eq.getSiteProduct(), null);   // снят с сайта или не в снимке
        } else if (eq.getRegistration() != null) {
            needsDetail = eq.getRegistration().getDetailFetchedAt() == null;
            card = ProductCards.ofRegistry(eq.getRegistration(), LocalDate.now(Market.KZ.zone()));
        } else {
            card = eq.getSpec() == null || eq.getSpec().isBlank() ? null : ProductCards.ofEquipment(eq);
        }
        return new ModelSnapshot(text, card, needsDetail, regNumber, views.model(eq), lot.getModelNote(), lot.getModelSetBy(),
                lot.getModelSetAt());
    }
}
