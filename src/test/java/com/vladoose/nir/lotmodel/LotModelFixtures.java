package com.vladoose.nir.lotmodel;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.TenderRepository;

/** Тендер с одним лотом для тестов «модели в работе». */
final class LotModelFixtures {
    private LotModelFixtures() {}

    static TenderLot lot(TenderRepository tenders, Market market, String name) {
        Tender t = Tender.builder().tenderNumber("ZZ-МОД-" + System.nanoTime()).status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(market).currency(market.currencyCode()).build();
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setLotNumber(1);
        l.setEquipName(name);
        t.getLots().add(l);
        tenders.saveAndFlush(t);
        return t.getLots().get(0);
    }
}
