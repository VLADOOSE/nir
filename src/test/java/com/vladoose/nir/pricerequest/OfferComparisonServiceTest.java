package com.vladoose.nir.pricerequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.OfferComparisonResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.OfferComparisonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class OfferComparisonServiceTest {

    @Autowired OfferComparisonService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender tender() {
        return tenderRepository.save(Tender.builder()
                .tenderNumber("T-" + System.nanoTime()).status("NEW").market(Market.KZ).build());
    }
    private TenderLot lot(Tender t, int num, String name) {
        return lotRepository.save(TenderLot.builder().tender(t).lotNumber(num).equipName(name).quantity(2).build());
    }
    private Distributor dist(String name) {
        return distributorRepository.save(Distributor.builder().name(name + System.nanoTime()).market(Market.KZ).build());
    }
    private PriceRequest pr(Tender t, Distributor d, List<PriceRequestItem> items) {
        PriceRequest p = PriceRequest.builder().tender(t).distributor(d).market(Market.KZ).status("RESPONDED").build();
        items.forEach(i -> i.setPriceRequest(p));
        p.setItems(items);
        return priceRequestRepository.save(p);
    }
    private PriceRequestItem item(TenderLot lot, String price) {
        return PriceRequestItem.builder().tenderLot(lot).requestedQuantity(lot.getQuantity())
                .responsePrice(price == null ? null : new BigDecimal(price)).build();
    }

    @Test
    void pivotsWithBestPerLotAndTotals() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ"), l2 = lot(t, 2, "Рентген");
        Distributor a = dist("A"), b = dist("B");
        PriceRequest pa = pr(t, a, List.of(item(l1, "100"), item(l2, "300")));  // A: l1=100, l2=300
        PriceRequest pb = pr(t, b, List.of(item(l1, "120"), item(l2, "250")));  // B: l1=120, l2=250

        OfferComparisonResponse r = service.build(t.getId());

        assertThat(r.getLots()).extracting(OfferComparisonResponse.Lot::getLotId)
                .containsExactlyInAnyOrder(l1.getId(), l2.getId());
        assertThat(r.getSuppliers()).extracting(OfferComparisonResponse.Supplier::getPriceRequestId)
                .containsExactlyInAnyOrder(pa.getId(), pb.getId());
        // мин по лоту: l1 → A(100), l2 → B(250)
        assertThat(r.getBestByLot().get(l1.getId())).isEqualTo(pa.getId());
        assertThat(r.getBestByLot().get(l2.getId())).isEqualTo(pb.getId());
        // итоги: A = 100*2 + 300*2 = 800; B = 120*2 + 250*2 = 740
        assertThat(r.getTotalsBySupplier().get(pa.getId())).isEqualByComparingTo("800");
        assertThat(r.getTotalsBySupplier().get(pb.getId())).isEqualByComparingTo("740");
    }

    @Test
    void tieBreakSmallerPriceRequestId() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ");
        PriceRequest pa = pr(t, dist("A"), List.of(item(l1, "100")));
        PriceRequest pb = pr(t, dist("B"), List.of(item(l1, "100"))); // равная цена
        OfferComparisonResponse r = service.build(t.getId());
        assertThat(r.getBestByLot().get(l1.getId())).isEqualTo(Math.min(pa.getId(), pb.getId()));
    }

    @Test
    void excludesSuppliersAndLotsWithoutPrices() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ"), l2 = lot(t, 2, "Рентген");
        PriceRequest priced = pr(t, dist("A"), List.of(item(l1, "100"), item(l2, null))); // l2 без цены
        PriceRequest empty = pr(t, dist("B"), List.of(item(l1, null)));                    // без цен вовсе
        OfferComparisonResponse r = service.build(t.getId());
        // поставщик без цен исключён
        assertThat(r.getSuppliers()).extracting(OfferComparisonResponse.Supplier::getPriceRequestId)
                .containsExactly(priced.getId());
        // лот l2 (без единой цены) исключён
        assertThat(r.getLots()).extracting(OfferComparisonResponse.Lot::getLotId).containsExactly(l1.getId());
    }

    @Test
    void emptyWhenNoResponses() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ");
        pr(t, dist("A"), List.of(item(l1, null)));
        OfferComparisonResponse r = service.build(t.getId());
        assertThat(r.getLots()).isEmpty();
        assertThat(r.getSuppliers()).isEmpty();
        assertThat(r.getCells()).isEmpty();
    }

    @Test
    void foreignMarketRejected() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        Long id = t.getId();
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.build(id)).isInstanceOf(NotFoundException.class);
    }
}
