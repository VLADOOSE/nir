package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderWorkStageServiceTest {

    @Autowired TenderWorkStageService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired ActivityApplyRepository applyRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private String u(String p) { return p + "-" + UUID.randomUUID().toString().substring(0, 8); }

    private Tender newTender() {
        Facility fac = facilityRepository.save(Facility.builder().name(u("Клин")).build());
        return tenderRepository.save(Tender.builder().tenderNumber(u("T")).facility(fac).status("ACTIVE").build());
    }

    private TenderLot lot(Tender t) {
        TenderLot lot = TenderLot.builder().tender(t).lotNumber(1).equipName(u("Лот")).quantity(1).build();
        t.getLots().add(lot);
        return tenderRepository.save(t).getLots().get(0);
    }

    private PriceRequest kp(Tender t, TenderLot lot, BigDecimal price) {
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        MedEquipment me = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор")).manufact("M").build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status("SENT").build();
        pr.getItems().add(PriceRequestItem.builder()
                .priceRequest(pr).tenderLot(lot).medEquipment(me).requestedQuantity(1).responsePrice(price).build());
        return priceRequestRepository.save(pr);
    }

    @Test
    void notStarted_absentFromMap() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        assertThat(service.stagesForMarket()).doesNotContainKey(t.getId());
    }

    @Test
    void kpWithoutPrice_isRequested() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        kp(t, lot(t), null);   // КП без цены
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.REQUESTED);
    }

    @Test
    void kpWithPrice_isPriced() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        kp(t, lot(t), BigDecimal.valueOf(100000));
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.PRICED);
    }

    @Test
    void applyWithItem_isWinnerSelected() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        TenderLot l = lot(t);
        MedEquipment me = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор")).manufact("M").build());
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        ActivityApply apply = ActivityApply.builder().tender(t).status("DRAFT").build();
        apply.getItems().add(ApplyItem.builder().apply(apply).tenderLot(l).medEquipment(me)
                .distributor(d).offeredCost(BigDecimal.valueOf(100000)).quantity(1).build());
        applyRepository.save(apply);
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.WINNER_SELECTED);
    }

    @Test
    void monotonic_highestStageWins() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        TenderLot l = lot(t);
        kp(t, l, BigDecimal.valueOf(100000));     // есть цена → PRICED
        MedEquipment me = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор")).manufact("M").build());
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        ActivityApply apply = ActivityApply.builder().tender(t).status("DRAFT").build();
        apply.getItems().add(ApplyItem.builder().apply(apply).tenderLot(l).medEquipment(me)
                .distributor(d).offeredCost(BigDecimal.valueOf(100000)).quantity(1).build());
        applyRepository.save(apply);              // + заявка → WINNER_SELECTED старше
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.WINNER_SELECTED);
    }

    @Test
    void marketIsolation_rfKpNotInKzMap() {
        MarketContext.set(Market.RF);
        Tender rf = newTender();
        kp(rf, lot(rf), BigDecimal.valueOf(100000));
        MarketContext.set(Market.KZ);
        Map<Long, WorkStage> kzMap = service.stagesForMarket();
        assertThat(kzMap).doesNotContainKey(rf.getId());
    }
}
