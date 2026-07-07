package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
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

    @Autowired LotSourcingService lotSourcingService;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired MedRegistryRepository medRegistryRepository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void hintsByProposedModelAndRegistryProducer() {
        MarketContext.set(Market.KZ);

        Distributor carrier = new Distributor();
        carrier.setName("ZZ Возит Mindray");
        carrier.setBrands(new java.util.ArrayList<>(List.of("Mindray")));
        distributorRepository.save(carrier);

        Distributor other = new Distributor();
        other.setName("ZZ Без брендов");
        distributorRepository.save(other);

        Tender tender = new Tender();
        tender.setTenderNumber("ZZ-SRC-1");
        tender.setStatus("ACTIVE");
        tenderRepository.save(tender);

        // лот A: есть предложенная модель с брендом-производителем
        MedEquipment eq = new MedEquipment();
        eq.setName("ZZ SonoMax");
        eq.setManufact("Shenzhen Mindray Bio-Medical");
        medEquipmentRepository.save(eq);
        TenderLot lotA = new TenderLot();
        lotA.setTender(tender);
        lotA.setLotNumber(1);
        lotA.setEquipName("ZZ УЗИ");
        lotA.setProposedEquipment(eq);
        tenderLotRepository.save(lotA);

        // лот B: без модели — производитель придёт из реестр-кандидата (имя лота = имя записи реестра)
        String regName = "Аппарат ультразвуковой диагностический ZZSONO-77";
        MedRegistry reg = new MedRegistry();
        reg.setRegNumber("ZZ-РУ-СРЦ-1");
        reg.setName(regName);
        reg.setProducer("Mindray Bio-Medical Co., Ltd");
        medRegistryRepository.saveAndFlush(reg);
        TenderLot lotB = new TenderLot();
        lotB.setTender(tender);
        lotB.setLotNumber(2);
        lotB.setEquipName(regName);
        tenderLotRepository.save(lotB);

        LotSourcingResponse r = lotSourcingService.build(tender.getId(), List.of(lotA.getId(), lotB.getId()), null);

        LotSourcingResponse.Entry carrierEntry = r.getDistributors().stream()
                .filter(e -> e.getDistributor().getName().equals("ZZ Возит Mindray"))
                .findFirst().orElseThrow();
        assertThat(carrierEntry.isPreselect()).isTrue();
        assertThat(carrierEntry.getMatchedBrands())
                .anyMatch(h -> h.getVia().equals("PROPOSED_MODEL") && h.getLotId().equals(lotA.getId()))
                .anyMatch(h -> h.getVia().equals("REGISTRY") && h.getLotId().equals(lotB.getId()));
        assertThat(carrierEntry.getMatchedBrands()).allMatch(h -> h.getBrand().equals("Mindray"));

        LotSourcingResponse.Entry otherEntry = r.getDistributors().stream()
                .filter(e -> e.getDistributor().getName().equals("ZZ Без брендов"))
                .findFirst().orElseThrow();
        assertThat(otherEntry.isPreselect()).isFalse();
        assertThat(otherEntry.getMatchedBrands()).isEmpty();
    }
}
