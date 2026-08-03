package com.vladoose.nir.integration;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.GoszakupTenderWriter;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.skpharmacy.SkAnnounce;
import com.vladoose.nir.integration.skpharmacy.SkLot;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyTenderWriter;
import com.vladoose.nir.repository.TenderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Слияние лотов при переимпорте проверяется НА УРОВНЕ БД (flush + clear + перечитывание).
 *
 * <p>Зачем отдельно от {@code ImportPreservesLotWorkTest}: там сверяется id объекта в памяти, а это
 * не ловит случай «Hibernate всё-таки удалил строку при flush, объект в коллекции остался». Именно
 * этот риск помечен в задаче как главный при {@code clear()} + {@code addAll()} на коллекции с
 * orphanRemoval. Здесь строка перечитывается из БД после сброса персистентного контекста.
 *
 * <p>Плюс закрыта ветка СК-Фармации (ключ слияния — код лота площадки), которую другие тесты
 * импорта переимпортом не прогоняют.
 */
@SpringBootTest
@Transactional
class ImportLotMergeFlushTest {

    @Autowired GoszakupTenderWriter goszakupWriter;
    @Autowired SkPharmacyTenderWriter skWriter;
    @Autowired TenderRepository tenderRepository;
    @PersistenceContext EntityManager em;

    @BeforeEach void setUp() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private long lotCount(String anno) {
        return (Long) em.createQuery("select count(l) from TenderLot l where l.tender.sourceExtId = :a")
                .setParameter("a", anno).getSingleResult();
    }

    // ---------- goszakup ----------

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
    void goszakupReimport_keptRowSurvivesFlush_droppedRowDeleted() {
        String anno = "MERGE-GZ-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("1", "Центрифуга", "лабораторная"), lot("2", "Морозильник", "низкотемпературный")));
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        TenderLot keeper = t.getLots().stream()
                .filter(x -> Integer.valueOf(1).equals(x.getLotNumber())).findFirst().orElseThrow();
        keeper.setRequiredSpec("разобранное ТЗ: скорость до 15000 об/мин");
        keeper.setTechSpecStatus(TechSpecStatus.OK);
        em.flush();
        Long keptId = keeper.getId();
        Long droppedId = t.getLots().stream()
                .filter(x -> Integer.valueOf(2).equals(x.getLotNumber())).findFirst().orElseThrow().getId();

        // переимпорт: лот 1 остался (с новым названием), лот 2 исчез с площадки
        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга обновлённая", "лабораторная")));
        em.flush();
        em.clear();   // дальше читаем из БД, а не из персистентного контекста

        TenderLot fromDb = em.find(TenderLot.class, keptId);
        assertThat(fromDb).as("строка выжившего лота удалена из БД").isNotNull();
        assertThat(fromDb.getRequiredSpec()).as("разобранное ТЗ уцелело в БД").contains("15000 об/мин");
        assertThat(fromDb.getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
        assertThat(fromDb.getEquipName()).as("поле площадки обновилось").isEqualTo("Центрифуга обновлённая");

        assertThat(em.find(TenderLot.class, droppedId)).as("выпавший лот не удалён").isNull();
        assertThat(lotCount(anno)).isEqualTo(1L);
    }

    /**
     * Два лота площадки с ОДИНАКОВЫМ номером не должны схлопнуться в один.
     *
     * <p>Слияние ищет существующий лот по номеру, поэтому оба таких лота нашли бы ОДНУ сущность,
     * она попадала бы в коллекцию дважды, а в БД оставалась одна строка — лот молча исчезал.
     * Замерено: со {@code get} в БД оказывалась 1 строка, тогда как прежний код (пересоздание)
     * давал 2. Лечится тем, что существующий лот забирается из карты через {@code remove}.
     */
    @Test
    void duplicateLotNumbersDoNotCollapseIntoOneRow() {
        String anno = "MERGE-DUP-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(lot("1", "Центрифуга", "лабораторная")));
        em.flush();

        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("1", "Центрифуга", "лабораторная"), lot("1", "Морозильник", "низкотемпературный")));
        em.flush();
        em.clear();

        assertThat(lotCount(anno)).as("оба лота площадки существуют, ни один не потерян").isEqualTo(2L);
    }

    // ---------- СК-Фармация ----------

    private SkAnnounce announce(String anno) {
        return new SkAnnounce("999999", anno, "ТОО «СК-Фармация»", "Тестовое объявление",
                "Тендер", "Товар", "2026-07-13 09:00:00", "2099-01-01 09:00:00",
                2, new BigDecimal("1000000"), "Опубликовано");
    }

    @Test
    void skReimport_mergesBySourceLotCode_andEnqueuesNewLot() {
        String anno = "MERGE-SK-" + System.nanoTime();
        skWriter.upsert(announce(anno), List.of(
                new SkLot("A-Т1", "Томограф компьютерный", new BigDecimal("500000"), 1),
                new SkLot("A-Т2", "Аппарат МРТ", new BigDecimal("700000"), 1)), null);
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        TenderLot keeper = t.getLots().stream()
                .filter(x -> "A-Т1".equals(x.getSourceLotCode())).findFirst().orElseThrow();
        keeper.setRequiredSpec("разобранное ТЗ: срез 128, ширина гентри 700 мм");
        keeper.setTechSpecStatus(TechSpecStatus.OK);
        em.flush();
        Long keptId = keeper.getId();
        Long droppedId = t.getLots().stream()
                .filter(x -> "A-Т2".equals(x.getSourceLotCode())).findFirst().orElseThrow().getId();

        // переимпорт: A-Т1 остался, A-Т2 исчез, A-Т3 добавился
        skWriter.upsert(announce(anno), List.of(
                new SkLot("A-Т1", "Томограф компьютерный 128", new BigDecimal("550000"), 2),
                new SkLot("A-Т3", "Аппарат рентгеновский", new BigDecimal("300000"), 1)), null);
        em.flush();
        em.clear();

        TenderLot fromDb = em.find(TenderLot.class, keptId);
        assertThat(fromDb).as("строка выжившего лота удалена из БД").isNotNull();
        assertThat(fromDb.getRequiredSpec()).as("разобранное ТЗ уцелело").contains("гентри 700 мм");
        assertThat(fromDb.getTechSpecStatus()).isEqualTo(TechSpecStatus.OK);
        assertThat(fromDb.getEquipName()).isEqualTo("Томограф компьютерный 128");
        assertThat(fromDb.getQuantity()).isEqualTo(2);

        assertThat(em.find(TenderLot.class, droppedId)).as("выпавший лот не удалён").isNull();
        assertThat(lotCount(anno)).isEqualTo(2L);

        TenderLot fresh = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().stream()
                .filter(x -> "A-Т3".equals(x.getSourceLotCode())).findFirst().orElseThrow();
        assertThat(fresh.getTechSpecStatus()).as("новый лот встал в очередь разбора ТЗ")
                .isEqualTo(TechSpecStatus.PENDING);
    }
}
