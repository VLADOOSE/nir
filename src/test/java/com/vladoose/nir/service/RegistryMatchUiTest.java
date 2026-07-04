package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RegistryMatchUiTest {

    @Autowired RegistryMatchService service;
    @Autowired TenderRepository tenderRepository;

    @BeforeEach
    void setUp() { MarketContext.set(Market.KZ); }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private Long lot(String equipName, String manufact, String requiredSpec) {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-UISC-" + System.nanoTime());
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName(equipName);
        l.setManufact(manufact);
        l.setRequiredSpec(requiredSpec);
        t.getLots().add(l);
        tenderRepository.save(t);
        return t.getLots().get(0).getId();
    }

    @Test
    void oneWordLot_notDistinctive_noTechSpec() {
        LotRegistryMatchResponse r = service.matchForLotUi(lot("Центрифуга", null, null), 5);
        assertThat(r.getCandidates()).isNotEmpty();
        assertThat(r.isDistinctive()).isFalse();       // 1 токен → % врёт
        assertThat(r.isTechSpecParsed()).isFalse();
    }

    @Test
    void multiWordLot_distinctive() {
        LotRegistryMatchResponse r = service.matchForLotUi(lot("Дефибриллятор монитор бифазный", null, null), 5);
        assertThat(r.getCandidates()).isNotEmpty();
        assertThat(r.isDistinctive()).isTrue();          // ≥2 токена
    }

    @Test
    void parsedTechSpec_distinctiveAndFlagged() {
        Long id = lot("Центрифуга", null, """
                Приложение 2
                характеристики
                закупаемых товаров:
                Максимальная скорость центрифугирования 4500 об/мин, вместимость 8 пробирок
                """);
        LotRegistryMatchResponse r = service.matchForLotUi(id, 5);
        assertThat(r.isTechSpecParsed()).isTrue();        // characteristics != null
        assertThat(r.isDistinctive()).isTrue();           // имя(1) + токены ТЗ ≥2
    }

    @Test
    void brandSet_distinctive() {
        LotRegistryMatchResponse r = service.matchForLotUi(lot("Монитор", "Mindray", null), 5);
        assertThat(r.isDistinctive()).isTrue();           // бренд-путь
    }
}
