package com.vladoose.nir.integration;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.GoszakupTenderWriter;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Переимпорт тендера не должен уничтожать работу оператора и результат разбора ТЗ.
 * До этой задачи rebuildLots делал clear() + создание заново — терялось всё.
 */
@SpringBootTest
@Transactional
class ImportPreservesLotWorkTest {

    @Autowired GoszakupTenderWriter writer;
    @Autowired TenderRepository tenderRepository;
    @Autowired MedEquipmentRepository equipmentRepository;

    @BeforeEach void setUp() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private TrdBuyDto trdBuy(String anno) {
        TrdBuyDto d = new TrdBuyDto();
        d.setNumberAnno(anno);
        d.setNameRu("Тестовая закупка");
        return d;
    }

    private LotDto lot(String number, String name, String descr) {
        LotDto l = new LotDto();
        l.setLotNumber(number);
        l.setNameRu(name);
        l.setDescriptionRu(descr);
        l.setCount(1);
        return l;
    }

    @Test
    void reimportKeepsParsedTechSpecAndOperatorChoices() {
        String anno = "REIMPORT-" + System.nanoTime();

        // первый импорт
        writer.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "лабораторная")));
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        TenderLot l = t.getLots().get(0);

        // оператор поработал: разобрал ТЗ и выбрал модель
        MedEquipment eq = new MedEquipment();
        eq.setName("Центрифуга LX-75");
        eq.setManufact("Тест");
        eq.setMarket(Market.KZ);
        equipmentRepository.saveAndFlush(eq);

        l.setRequiredSpec("характеристики закупаемых товаров: центрифуга лабораторная охлаждаемая, "
                + "скорость до 15000 об/мин, ротор угловой");
        l.setTechSpecStatus(TechSpecStatus.OK);
        l.setProposedEquipment(eq);
        l.setMaxWeightKg(new java.math.BigDecimal("55.00"));
        tenderRepository.saveAndFlush(t);
        Long lotIdBefore = l.getId();

        // переимпорт того же тендера
        writer.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "лабораторная")));

        Tender after = tenderRepository.findBySourceExtId(anno).orElseThrow();
        assertThat(after.getLots()).hasSize(1);
        TenderLot kept = after.getLots().get(0);

        assertThat(kept.getId()).as("лот не пересоздан").isEqualTo(lotIdBefore);
        assertThat(kept.getRequiredSpec()).as("разобранное ТЗ уцелело").contains("15000 об/мин");
        assertThat(kept.getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
        assertThat(kept.getProposedEquipment()).isNotNull();
        assertThat(kept.getMaxWeightKg()).isNotNull();
    }

    /** Новый лот в переимпорте должен появиться и сразу встать в очередь на разбор ТЗ. */
    @Test
    void newLotIsAddedAndEnqueued() {
        String anno = "REIMPORT-ADD-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "лабораторная")));

        writer.upsertOne(trdBuy(anno), null,
                List.of(lot("1", "Центрифуга", "лабораторная"), lot("2", "Морозильник", "низкотемпературный")));

        Tender after = tenderRepository.findBySourceExtId(anno).orElseThrow();
        assertThat(after.getLots()).hasSize(2);
        TenderLot fresh = after.getLots().stream()
                .filter(x -> "Морозильник".equals(x.getEquipName())).findFirst().orElseThrow();
        assertThat(fresh.getTechSpecStatus()).isEqualTo(TechSpecStatus.PENDING);
    }

    /** Лот, исчезнувший с площадки, должен исчезнуть и у нас. */
    @Test
    void removedLotDisappears() {
        String anno = "REIMPORT-DEL-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null,
                List.of(lot("1", "Центрифуга", "лабораторная"), lot("2", "Морозильник", "низкотемпературный")));

        writer.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "лабораторная")));

        Tender after = tenderRepository.findBySourceExtId(anno).orElseThrow();
        assertThat(after.getLots()).hasSize(1);
        assertThat(after.getLots().get(0).getEquipName()).isEqualTo("Центрифуга");
    }

    /** Цена и количество с площадки — наоборот, должны обновляться. */
    @Test
    void importedFieldsAreRefreshed() {
        String anno = "REIMPORT-UPD-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "лабораторная")));

        LotDto updated = lot("1", "Центрифуга лабораторная", "лабораторная охлаждаемая");
        updated.setCount(7);
        writer.upsertOne(trdBuy(anno), null, List.of(updated));

        TenderLot kept = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);
        assertThat(kept.getQuantity()).isEqualTo(7);
        assertThat(kept.getEquipName()).isEqualTo("Центрифуга лабораторная");
    }

    /** Пока ТЗ не разобрано, описание с площадки обновляется свободно. */
    @Test
    void descriptionRefreshedWhileTechSpecNotParsed() {
        String anno = "REIMPORT-DESC-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "старое описание")));

        writer.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "новое описание")));

        TenderLot kept = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);
        assertThat(kept.getRequiredSpec()).isEqualTo("новое описание");
    }
}
