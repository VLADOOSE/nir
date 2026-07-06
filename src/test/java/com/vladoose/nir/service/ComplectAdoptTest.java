package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ComplectAdoptTest {

    @Autowired ComplectService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired RegistryComponentRepository componentRepository;
    @Autowired MedEquipmentRepository equipmentRepository;

    MedRegistry apparatus;
    Long lotId;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        apparatus = registryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РК МИ (МТ)-ADOPTC-1").name("ZZ Аппарат ЭЛЭСКУЛАПZZ").producer("Мед ТеКо")
                .country("РОССИЯ").build());
        componentRepository.saveAndFlush(RegistryComponent.builder()
                .regNumber("ZZ-РК МИ (МТ)-ADOPTC-1").partNumber(4)
                .productName("4.Электроды силиконовые электропроводящие 55 х 80")
                .component("комплектующие").producer("ООО «Мед ТеКо»").country("Россия")
                .fetchedAt(OffsetDateTime.now()).build());
        Tender t = new Tender();
        t.setTenderNumber("ZZ-ADOPTC-" + System.nanoTime());
        t.setStatus("ACTIVE");
        TenderLot l = new TenderLot();
        l.setTender(t); l.setEquipName("Электрод");
        t.getLots().add(l);
        tenderRepository.saveAndFlush(t);
        lotId = t.getLots().get(0).getId();
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    @Test
    void adoptComponent_createsCatalogItem_withComponentNameAndApparatusReg() {
        TenderLot lot = service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 4);

        MedEquipment eq = lot.getProposedEquipment();
        assertThat(eq).isNotNull();
        assertThat(eq.getName()).contains("силиконовые");                 // имя из компонента, не из аппарата
        assertThat(eq.getManufact()).isEqualTo("ООО «Мед ТеКо»");         // производитель компонента
        assertThat(eq.getRegistration().getRegNumber()).isEqualTo("ZZ-РК МИ (МТ)-ADOPTC-1"); // РУ аппарата
        assertThat(eq.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(eq.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void adoptComponent_twice_reusesSameCatalogItem() {
        service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 4);
        long before = equipmentRepository.count();
        service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 4);
        assertThat(equipmentRepository.count()).isEqualTo(before);        // дубль не плодится
    }

    @Test
    void adoptComponent_uncachedPart_throws404() {
        assertThatThrownBy(() -> service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 99))
                .isInstanceOf(NotFoundException.class);
    }
}
