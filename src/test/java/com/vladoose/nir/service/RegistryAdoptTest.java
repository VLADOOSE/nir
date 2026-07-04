package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RegistryAdoptTest {

    @Autowired RegistryMatchService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired MedRegistryRepository medRegistryRepository;

    MedRegistry reg;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        reg = medRegistryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РУ-ADOPT-1")
                .name(("ZZ Оцифровщик рентгеновских изображений " + "x".repeat(300)))
                .producer(null) // проверяем NOT NULL-заглушку manufact
                .build());
        lot = makeLot("ZZ Устройство оцифровки");
    }

    private TenderLot makeLot(String name) {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-ADOPT-" + System.nanoTime());
        t.setStatus("ACTIVE");
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName(name);
        t.getLots().add(l);
        tenderRepository.save(t);
        return t.getLots().get(0);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void adoptCreatesCatalogItemAndProposesForLot() {
        TenderLot updated = service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1");

        MedEquipment eq = updated.getProposedEquipment();
        assertThat(eq).isNotNull();
        assertThat(eq.getName()).hasSizeLessThanOrEqualTo(255).startsWith("ZZ Оцифровщик");
        assertThat(eq.getManufact()).isEqualTo("не указан"); // producer=null
        assertThat(eq.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(eq.getRegistration().getRegNumber()).isEqualTo("ZZ-РУ-ADOPT-1");
        assertThat(eq.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void adoptSameRegNumberTwice_reusesCatalogItem() {
        service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1");
        TenderLot lot2 = makeLot("ZZ Второй лот");
        service.adoptForLot(lot2.getId(), "ZZ-РУ-ADOPT-1");

        long count = medEquipmentRepository.findAll().stream()
                .filter(e -> e.getRegistration() != null
                        && "ZZ-РУ-ADOPT-1".equals(e.getRegistration().getRegNumber()))
                .count();
        assertThat(count).isEqualTo(1); // дубль не создан
    }

    @Test
    void adoptAnotherRegNumber_replacesProposedModel() {
        medRegistryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РУ-ADOPT-2").name("ZZ Сканер пластин").producer("ZZ Producer").build());
        service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1");
        TenderLot updated = service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-2");
        assertThat(updated.getProposedEquipment().getRegistration().getRegNumber()).isEqualTo("ZZ-РУ-ADOPT-2");
        assertThat(updated.getProposedEquipment().getManufact()).isEqualTo("ZZ Producer");
    }

    @Test
    void unknownRegNumberRejected() {
        assertThatThrownBy(() -> service.adoptForLot(lot.getId(), "ZZ-НЕТ-ТАКОГО"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void foreignMarketLotHidden() {
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1"))
                .isInstanceOf(NotFoundException.class);
    }
}
