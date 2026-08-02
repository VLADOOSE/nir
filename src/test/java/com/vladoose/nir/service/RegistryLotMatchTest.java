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

    /**
     * Выдача не должна зависеть от {@code limit}. Скоры ложатся на дискретную решётку, поэтому
     * совпадения бит-в-бит массовые: у «Магнитно-резонансный томограф (безгелиевый)» 11+ записей
     * с одинаковым 0.393939. Пока в {@code ORDER BY} не было второго ключа, какая из них попадёт
     * в топ-5, решал heapsort — тот же лот при limit=5/6/10 давал РАЗНЫЙ топ-5, а метрики гейта
     * (limit=5) мерили не то, что видит оператор: {@code TenderLotController} зовёт
     * {@code matchForLotUi(id, min(limit, 20))}. Тай-брейк по reg_number это чинит.
     */
    @Test
    void topFiveDoesNotDependOnLimit() {
        TenderLot lot = savedLot("Магнитно-резонансный томограф (безгелиевый)", null, null);

        List<String> atFive = registryMatchService.candidatesForLot(lot.getId(), 5)
                .stream().map(RegistryCandidateResponse::getRegNumber).toList();
        List<String> firstFiveOfTwenty = registryMatchService.candidatesForLot(lot.getId(), 20)
                .stream().map(RegistryCandidateResponse::getRegNumber).limit(5).toList();

        assertThat(atFive).hasSize(5).isEqualTo(firstFiveOfTwenty);
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
    void golden_manufactSet_usesOldBrandPath() {
        TenderLot lot = savedLot("Монитор пациента", "Mindray", null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 5);
        assertThat(top).isNotEmpty();
        // бренд-путь: производитель в топе содержит Mindray
        assertThat(top.get(0).getProducer().toLowerCase()).contains("mindray");
    }
}
