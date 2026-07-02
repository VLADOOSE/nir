package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Кандидаты реестра НЦЭЛС по лоту тендера (реальный реестр ~14k в nirdb). */
@SpringBootTest
@Transactional
class RegistryLotMatchTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired RegistryMatchService registryMatchService;

    @BeforeEach
    void setUp() { MarketContext.set(Market.KZ); }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void candidatesForLot_matchesRegistryByLotName() {
        Tender t = new Tender();
        t.setTenderNumber("LOT-REG-1");
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setEquipName("Аппарат ультразвуковой диагностический");
        t.getLots().add(lot);
        tenderRepository.save(t);

        List<RegistryCandidateResponse> candidates =
                registryMatchService.candidatesForLot(t.getLots().get(0).getId(), 5);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).getRegNumber()).isNotBlank();
        assertThat(candidates.get(0).getScore()).isGreaterThan(0.0);
    }

    @Test
    void candidatesForLot_unknownLot_throwsNotFound() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> registryMatchService.candidatesForLot(999999L, 5))
                .isInstanceOf(com.vladoose.nir.exception.NotFoundException.class);
    }
}
