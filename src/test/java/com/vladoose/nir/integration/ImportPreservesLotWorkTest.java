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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Переимпорт тендера не должен уничтожать работу оператора и результат разбора ТЗ.
 * До этой задачи rebuildLots делал clear() + создание заново — терялось всё.
 *
 * <p>⚠ Номера лотов здесь ЖИВОЙ формы («87197521-ОИ2»), а не «1»/«2». Живой goszakup отдаёт
 * номер с суффиксом (см. {@code GoszakupDtoJsonTest}), в {@code Integer} он не разбирается —
 * на синтетических числовых номерах тест зеленел бы, а на реальных данных слияние не работало.
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
        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная")));
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
        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная")));

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
        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная")));

        writer.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная"),
                        lot("87197521-ОИ3", "Морозильник", "низкотемпературный")));

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
                List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная"),
                        lot("87197521-ОИ3", "Морозильник", "низкотемпературный")));

        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная")));

        Tender after = tenderRepository.findBySourceExtId(anno).orElseThrow();
        assertThat(after.getLots()).hasSize(1);
        assertThat(after.getLots().get(0).getEquipName()).isEqualTo("Центрифуга");
    }

    /** Цена и количество с площадки — наоборот, должны обновляться. */
    @Test
    void importedFieldsAreRefreshed() {
        String anno = "REIMPORT-UPD-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная")));

        LotDto updated = lot("87197521-ОИ2", "Центрифуга лабораторная", "лабораторная охлаждаемая");
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
        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "старое описание")));

        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "новое описание")));

        TenderLot kept = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);
        assertThat(kept.getRequiredSpec()).isEqualTo("новое описание");
    }

    /** Живой номер лота обязан ДОЕХАТЬ до БД — иначе слияния при следующем импорте не будет. */
    @Test
    void livePlatformLotNumberIsPersistedAsMergeKey() {
        String anno = "REIMPORT-KEY-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная")));

        TenderLot kept = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);
        assertThat(kept.getSourceLotCode()).isEqualTo("87197521-ОИ2");
        assertThat(kept.getLotNumber()).as("нечисловой номер в int не разбирается — остаётся пустым").isNull();
    }

    /** Числовой номер по-прежнему попадает в отображаемое поле. */
    @Test
    void numericLotNumberStillFillsDisplayField() {
        String anno = "REIMPORT-NUM-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot("4", "Центрифуга", "лабораторная")));

        TenderLot kept = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);
        assertThat(kept.getLotNumber()).isEqualTo(4);
        assertThat(kept.getSourceLotCode()).isEqualTo("4");
    }

    /** Без номера лота ключа нет — слияние невозможно, лот пересоздаётся (эта ветка должна остаться покрытой). */
    @Test
    void lotWithoutAnyCodeIsRecreated() {
        String anno = "REIMPORT-NOKEY-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot(null, "Центрифуга", "лабораторная")));
        Long before = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0).getId();

        // имя тоже меняем, иначе сработает запасной матч по наименованию
        writer.upsertOne(trdBuy(anno), null, List.of(lot(null, "Совсем другой лот", "описание")));

        TenderLot after = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);
        assertThat(after.getId()).isNotEqualTo(before);
        assertThat(after.getTechSpecStatus()).isEqualTo(TechSpecStatus.PENDING);
    }

    /**
     * Переходный случай, ради которого всё и делается: в живой БД у ВСЕХ goszakup-лотов
     * {@code source_lot_code} пуст. Такой лот обязан найтись по имени, сохранить разобранное ТЗ
     * и получить код — иначе первый же переимпорт после этой правки уничтожил бы ровно те данные,
     * которые она защищает.
     */
    @Test
    void legacyLotWithoutCodeIsMergedByNameAndGetsCode() {
        String anno = "REIMPORT-LEGACY-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of(lot(null, "Датчик ультразвуковой", "краткое описание")));
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        TenderLot legacy = t.getLots().get(0);
        legacy.setSourceLotCode(null);                       // как у 1305 живых лотов
        legacy.setRequiredSpec("разобранное ТЗ: диапазон 2.0-9.0 МГц, конвексный");
        legacy.setTechSpecStatus(TechSpecStatus.OK);
        tenderRepository.saveAndFlush(t);
        Long legacyId = legacy.getId();

        writer.upsertOne(trdBuy(anno), null,
                List.of(lot("17304732-Т1", "Датчик ультразвуковой", "краткое описание")));

        TenderLot kept = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);
        assertThat(kept.getId()).as("лот найден по имени, а не пересоздан").isEqualTo(legacyId);
        assertThat(kept.getRequiredSpec()).contains("2.0-9.0 МГц");
        assertThat(kept.getSourceLotCode()).as("код проставлен — дальше сливаемся по нему").isEqualTo("17304732-Т1");
    }

    /**
     * Пустой список лотов = сбой получения (пустой/битый ответ /lots), а не «лотов больше нет».
     * Молчаливое удаление здесь стоило бы всей работы оператора по тендеру.
     */
    @Test
    void emptyLotListDoesNotWipeExistingLots() {
        String anno = "REIMPORT-EMPTY-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная"),
                        lot("87197521-ОИ3", "Морозильник", "низкотемпературный")));

        assertThatThrownBy(() -> writer.upsertOne(trdBuy(anno), null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 лотов");

        assertThat(tenderRepository.findBySourceExtId(anno).orElseThrow().getLots())
                .as("лоты на месте").hasSize(2);
    }

    /** У тендера без лотов пустой список — не сбой, а норма (новое объявление без лотов). */
    @Test
    void emptyLotListIsFineWhenTenderHadNoLots() {
        String anno = "REIMPORT-EMPTY-OK-" + System.nanoTime();
        writer.upsertOne(trdBuy(anno), null, List.of());
        assertThat(tenderRepository.findBySourceExtId(anno).orElseThrow().getLots()).isEmpty();
    }
}
