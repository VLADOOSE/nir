package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.EquipmentMatchResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EquipmentScoringSpecDerivedTest {

    private static final double[] W = {0.25, 0.25, 0.25, 0.25};

    @Autowired EquipmentScoringService scoringService;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;

    private Tender tender;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tender = new Tender();
        tender.setTenderNumber("ZZ-SD-" + System.nanoTime());
        tender.setStatus("ACTIVE");
        tenderRepository.save(tender);
        saveEquip("ZZ Компакт", 1000, 700, 1200, "40");
        saveEquip("ZZ Гигант", 2000, 900, 1500, "60");
    }

    private void saveEquip(String name, int len, int wid, int hei, String kg) {
        MedEquipment e = new MedEquipment();
        e.setName(name);
        e.setManufact("ZZBrand");
        e.setLengthMm(len);
        e.setWidthMm(wid);
        e.setHeightMm(hei);
        e.setWeightKg(new BigDecimal(kg));
        medEquipmentRepository.save(e);
    }

    private TenderLot saveLot(Integer maxLen, String spec) {
        TenderLot lot = new TenderLot();
        lot.setTender(tender);
        lot.setLotNumber(1);
        lot.setEquipName("ZZ УЗИ");
        lot.setMaxLengthMm(maxLen);
        lot.setRequiredSpec(spec);
        return tenderLotRepository.save(lot);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void specConstraintsFilterCandidates_andSpecDerivedReturned() {
        TenderLot lot = saveLot(null, "Габариты не более 1200х800х1300 мм, вес не более 45 кг");
        EquipmentMatchResponse r = scoringService.scoreLot(lot.getId(), W, "BALANCED");

        List<String> names = r.getCandidates().stream()
                .map(EquipmentMatchResponse.Candidate::getName).toList();
        assertThat(names).contains("ZZ Компакт").doesNotContain("ZZ Гигант");

        assertThat(r.getSpecDerived()).isNotNull();
        assertThat(r.getSpecDerived().getLengthMm()).isEqualTo(1200);
        assertThat(r.getSpecDerived().getWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
        assertThat(r.getSpecDerived().getSnippets()).isNotEmpty();
    }

    @Test
    void structuredFieldsTakePriority_specDerivedNull() {
        // структурное поле есть → парсер не применяется, даже если в спеке другие числа
        TenderLot lot = saveLot(800, "Габариты не более 5000х5000х5000 мм");
        EquipmentMatchResponse r = scoringService.scoreLot(lot.getId(), W, "BALANCED");

        List<String> names = r.getCandidates().stream()
                .map(EquipmentMatchResponse.Candidate::getName).toList();
        assertThat(names).doesNotContain("ZZ Компакт", "ZZ Гигант"); // оба длиннее 800
        assertThat(r.getSpecDerived()).isNull();
    }

    @Test
    void unparsableSpec_specDerivedNull_noFiltering_butNoCriteria() {
        // ни типа, ни структурных габаритов, спека не парсится → нет критериев отбора:
        // подбор НЕ вываливает весь каталог, а сигналит noCriteria
        TenderLot lot = saveLot(null, "Класс безопасности IIa, питание 220 В");
        EquipmentMatchResponse r = scoringService.scoreLot(lot.getId(), W, "BALANCED");

        assertThat(r.isNoCriteria()).isTrue();
        assertThat(r.getCandidates()).isEmpty();
        assertThat(r.getSpecDerived()).isNull();
    }

    @Test
    void withSpecDimensions_hasCriteria() {
        TenderLot lot = saveLot(null, "Габариты не более 1200х800х1300 мм");
        EquipmentMatchResponse r = scoringService.scoreLot(lot.getId(), W, "BALANCED");
        assertThat(r.isNoCriteria()).isFalse();
        assertThat(r.getCandidates()).isNotEmpty();
    }
}
