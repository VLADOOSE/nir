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

    // ===== золотой набор: реальные канцелярские имена лотов против живого реестра (~14k) =====

    private TenderLot savedLot(String equipName, String manufact, String requiredSpec) {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-GOLD-" + System.nanoTime());
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setEquipName(equipName);
        lot.setManufact(manufact);
        lot.setRequiredSpec(requiredSpec);
        t.getLots().add(lot);
        tenderRepository.save(t);
        return t.getLots().get(0);
    }

    @Test
    void golden_xrayDigitizer_findsRegistryModels() {
        TenderLot lot = savedLot("Устройство оцифровки рентген снимков", null, null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 5);
        assertThat(top).isNotEmpty();
        assertThat(top).anyMatch(c -> {
            String n = c.getName().toLowerCase();
            return n.contains("оцифровщик") || (n.contains("рентген") && n.contains("снимк"));
        });
    }

    @Test
    void golden_defibrillatorMonitor_topContainsDefibrillator() {
        TenderLot lot = savedLot("Дефибриллятор-монитор бифазный портативный", null, null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 3);
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).getName().toLowerCase()).contains("дефибриллятор");
    }

    @Test
    void golden_shortName_pulseOximeter_stillWorks() {
        TenderLot lot = savedLot("Пульсоксиметр", null, null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 3);
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).getName().toLowerCase()).contains("пульсоксиметр");
    }

    @Test
    void golden_electrode_enrichedFromParsedTechSpec() {
        TenderLot lot = savedLot("Электрод", null, """
                Приложение 2
                Описание и требуемые функциональные, технические, качественные и эксплуатационные
                характеристики
                закупаемых товаров:
                Резиновые пластинки для аппарата электрофореза "Элэскулап", размеры 55*80 мм
                """);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 5);
        assertThat(top).isNotEmpty();
        // сигнал из ТЗ: «пластин(ки)» / электрофорез / элэскулап — без обогащения имя «Электрод»
        // дало бы только ЭКГ/хирургические электроды
        assertThat(top).anyMatch(c -> {
            String n = c.getName().toLowerCase();
            return n.contains("электрофорез") || n.contains("элэскулап") || n.contains("пластин");
        });
    }

    @Test
    void golden_manufactSet_usesOldBrandPath() {
        TenderLot lot = savedLot("Монитор пациента", "Mindray", null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 5);
        assertThat(top).isNotEmpty();
        // бренд-путь: производитель в топе содержит Mindray
        assertThat(top.get(0).getProducer().toLowerCase()).contains("mindray");
    }
}
