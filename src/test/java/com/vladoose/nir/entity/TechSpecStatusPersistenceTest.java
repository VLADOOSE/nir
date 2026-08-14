package com.vladoose.nir.entity;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** V14: поля статуса разбора ТЗ доезжают до БД. */
@SpringBootTest
@Transactional
class TechSpecStatusPersistenceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;

    @BeforeEach void setUp() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    @Test
    void persistsStatusAndAttemptedAt() {
        Tender t = new Tender();
        t.setTenderNumber("TS-STATUS-1");
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        tenderRepository.save(t);

        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setEquipName("Центрифуга");
        lot.setTechSpecStatus(TechSpecStatus.PENDING);
        lot.setTechSpecAttemptedAt(OffsetDateTime.now());
        lotRepository.save(lot);
        lotRepository.flush();

        TenderLot reloaded = lotRepository.findById(lot.getId()).orElseThrow();
        assertThat(reloaded.getTechSpecStatus()).isEqualTo(TechSpecStatus.PENDING);
        assertThat(reloaded.getTechSpecAttemptedAt()).isNotNull();
    }
}
