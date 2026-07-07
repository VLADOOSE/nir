package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LotSourcingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LotSourcingServiceTest {

    @Autowired LotSourcingService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired EquipmentTypeRepository typeRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private EquipmentType type(String name) {
        return typeRepository.findAll().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void typeHitRanksAboveNonRelevant() {
        MarketContext.set(Market.KZ);
        EquipmentType ivl = type("ИВЛ");
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + System.nanoTime())
                .status("NEW").market(Market.KZ).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1)
                .equipName("Аппарат искусственной вентиляции лёгких").quantity(1).equipmentType(ivl).build());

        Distributor typed = distributorRepository.save(Distributor.builder()
                .name("Профильный ИВЛ " + System.nanoTime()).market(Market.KZ)
                .equipmentTypes(List.of(ivl)).build());
        Distributor other = distributorRepository.save(Distributor.builder()
                .name("Непрофильный " + System.nanoTime()).market(Market.KZ).build());

        LotSourcingResponse resp = service.build(t.getId(), List.of(lot.getId()), null);

        LotSourcingResponse.Entry typedEntry = resp.getDistributors().stream()
                .filter(e -> e.getDistributor().getId().equals(typed.getId())).findFirst().orElseThrow();
        LotSourcingResponse.Entry otherEntry = resp.getDistributors().stream()
                .filter(e -> e.getDistributor().getId().equals(other.getId())).findFirst().orElseThrow();

        assertThat(typedEntry.isRelevant()).isTrue();
        assertThat(typedEntry.getReasons()).anyMatch(r -> r.getKind().equals("TYPE") && r.getLabel().equals("ИВЛ"));
        assertThat(otherEntry.isRelevant()).isFalse();
        assertThat(resp.getDistributors().indexOf(typedEntry)).isLessThan(resp.getDistributors().indexOf(otherEntry));
        assertThat(resp.isSingleLot()).isTrue();
        assertThat(resp.getDetectedType()).isNotNull();
        assertThat(resp.getDetectedType().getName()).isEqualTo("ИВЛ");
    }

    @Test
    void tier2AccessoryTermFindsApparatusBrand() {
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + System.nanoTime())
                .status("NEW").market(Market.KZ).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1)
                .equipName("Электроды силиконовые для аппарата «Элэскулап» 55×80").quantity(1).build());

        Distributor carriesApparatus = distributorRepository.save(Distributor.builder()
                .name("Возит Элэскулап " + System.nanoTime()).market(Market.KZ)
                .brands(List.of("Элэскулап")).build());

        LotSourcingResponse resp = service.build(t.getId(), List.of(lot.getId()), null);

        assertThat(resp.getSourcingTerm()).containsIgnoringCase("элэскулап");
        LotSourcingResponse.Entry e = resp.getDistributors().stream()
                .filter(x -> x.getDistributor().getId().equals(carriesApparatus.getId())).findFirst().orElseThrow();
        assertThat(e.isRelevant()).isTrue();
        assertThat(e.getReasons()).anyMatch(r -> r.getKind().equals("BRAND"));
    }

    @Test
    void manualTermOverridesAuto() {
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + System.nanoTime())
                .status("NEW").market(Market.KZ).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1)
                .equipName("Расходный материал").quantity(1).build());
        Distributor d = distributorRepository.save(Distributor.builder()
                .name("Возит Mindray " + System.nanoTime()).market(Market.KZ)
                .brands(List.of("Mindray")).build());

        LotSourcingResponse resp = service.build(t.getId(), List.of(lot.getId()), "Mindray");

        assertThat(resp.getSourcingTerm()).isEqualTo("Mindray");
        assertThat(resp.getDistributors().stream()
                .filter(x -> x.getDistributor().getId().equals(d.getId())).findFirst().orElseThrow()
                .isRelevant()).isTrue();
    }
}
