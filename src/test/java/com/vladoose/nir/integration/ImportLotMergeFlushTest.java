package com.vladoose.nir.integration;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderLotController;
import com.vladoose.nir.dto.request.TenderLotRequest;
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
import com.vladoose.nir.service.TechSpecWriter;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired TechSpecWriter techSpecWriter;
    @Autowired TenderLotController lotController;
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
                List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная"),
                        lot("87197521-ОИ3", "Морозильник", "низкотемпературный")));
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        TenderLot keeper = t.getLots().stream()
                .filter(x -> "87197521-ОИ2".equals(x.getSourceLotCode())).findFirst().orElseThrow();
        keeper.setRequiredSpec("разобранное ТЗ: скорость до 15000 об/мин");
        keeper.setTechSpecStatus(TechSpecStatus.OK);
        em.flush();
        Long keptId = keeper.getId();
        Long droppedId = t.getLots().stream()
                .filter(x -> "87197521-ОИ3".equals(x.getSourceLotCode())).findFirst().orElseThrow().getId();

        // переимпорт: лот ОИ2 остался (с новым названием), лот ОИ3 исчез с площадки
        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Центрифуга обновлённая", "лабораторная")));
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
     * Два лота площадки с ОДИНАКОВЫМ ключом не должны схлопнуться в один.
     *
     * <p>Слияние ищет существующий лот по ключу, поэтому оба таких лота нашли бы ОДНУ сущность,
     * она попадала бы в коллекцию дважды, а в БД оставалась одна строка — лот молча исчезал.
     * Замерено: при поиске без изъятия в БД оказывалась 1 строка, тогда как прежний код
     * (пересоздание) давал 2. Лечится тем, что существующий лот ЗАБИРАЕТСЯ из индекса.
     */
    @Test
    void duplicateLotNumbersDoNotCollapseIntoOneRow() {
        String anno = "MERGE-DUP-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная")));
        em.flush();

        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная"),
                        lot("87197521-ОИ2", "Морозильник", "низкотемпературный")));
        em.flush();
        em.clear();

        assertThat(lotCount(anno)).as("оба лота площадки существуют, ни один не потерян").isEqualTo(2L);
    }

    /**
     * Симметричный случай на стороне СУЩЕСТВУЮЩИХ строк: два лота с одним ключом раньше затирали
     * друг друга в карте (last-wins), и незабранный удалялся orphanRemoval при живом счётчике.
     */
    @Test
    void twoExistingRowsWithSameKeySurviveReimport() {
        String anno = "MERGE-DUP2-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная"),
                        lot("87197521-ОИ2", "Центрифуга", "лабораторная")));
        em.flush();
        assertThat(lotCount(anno)).isEqualTo(2L);

        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Центрифуга", "лабораторная"),
                        lot("87197521-ОИ2", "Центрифуга", "лабораторная")));
        em.flush();
        em.clear();

        assertThat(lotCount(anno)).as("обе существующие строки забраны, ни одна не осиротела").isEqualTo(2L);
    }

    /**
     * Сквозной случай C2: разбор ТЗ кнопкой «ТЗ» помечает лот как разобранный, и переимпорт
     * НЕ затирает многотысячную техспеку однострочным description_ru с площадки.
     * Без отметки {@code TechSpecStatus.OK} в {@link TechSpecWriter} гард в райтере был декоративным.
     */
    @Test
    void parsedTechSpecSurvivesReimport_endToEnd() {
        String anno = "MERGE-TS-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Датчик ультразвуковой", "Датчик ультразвуковой")));
        em.flush();
        TenderLot lot = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);

        // так ТЗ попадает в лот в проде — через TechSpecWriter (кнопка «ТЗ» и будущая очередь)
        techSpecWriter.apply(lot.getId(), "ТЕХНИЧЕСКАЯ СПЕЦИФИКАЦИЯ: датчик конвексный, 2.0-9.0 МГц, "
                + "глубина сканирования не менее 240 мм", null);
        em.flush();
        assertThat(em.find(TenderLot.class, lot.getId()).getTechSpecStatus())
                .as("разбор ТЗ помечает лот разобранным").isEqualTo(TechSpecStatus.OK);

        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("87197521-ОИ2", "Датчик ультразвуковой", "Датчик ультразвуковой")));
        em.flush();
        em.clear();

        assertThat(em.find(TenderLot.class, lot.getId()).getRequiredSpec())
                .as("техспека не затёрта коротким описанием площадки").contains("2.0-9.0 МГц");
    }

    /**
     * Живой случай мис-привязки: тендер 17294802-1 — шесть лотов «Набор реагентов», у одного
     * разобранное ТЗ (1724 симв.), и именно он из-за UPDATE уехал в конец кучи, поэтому приходил
     * из БД ПОСЛЕДНИМ. Слияние по неоднозначному имени спарило бы входящий лот №1 с чужой строкой,
     * затерев её ТЗ коротким описанием, а разобранное ТЗ предъявило бы под лотом №6 с его
     * количеством и ценой.
     *
     * <p>Тест утверждает ПРИВЯЗКУ, а не выживание текста: замер, считающий только «сколько ТЗ
     * уцелело», такую ошибку увидеть не может в принципе.
     */
    @Test
    void ambiguousNamesDoNotMisattachParsedSpec() {
        String anno = "MERGE-AMBIG-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(
                lot(null, "Набор реагентов", "описание с площадки"),
                lot(null, "Набор реагентов", "описание с площадки")));
        em.flush();
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        // у всех исторических лотов кода нет — слияние пошло бы на 100% по имени
        for (TenderLot l : t.getLots()) l.setSourceLotCode(null);
        TenderLot parsed = t.getLots().get(0);
        parsed.setRequiredSpec("разобранное ТЗ: набор реагентов для определения глюкозы, 400 определений");
        parsed.setTechSpecStatus(TechSpecStatus.OK);
        em.flush();
        Long parsedId = parsed.getId();

        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(
                lot("17294802-ОИ1", "Набор реагентов", "описание с площадки"),
                lot("17294802-ОИ2", "Набор реагентов", "описание с площадки")));
        em.flush();
        em.clear();

        // неоднозначное имя соответствием не считается: строки пересозданы, ТЗ потеряно ВИДИМО,
        // но ни одна строка не получила чужое ТЗ вместе с чужими количеством и ценой
        TenderLot old = em.find(TenderLot.class, parsedId);
        assertThat(old).as("строка со старым ТЗ не сливалась вслепую").isNull();
        assertThat(lotCount(anno)).isEqualTo(2L);
        assertThat(tenderRepository.findBySourceExtId(anno).orElseThrow().getLots())
                .as("ни на один лот не переклеено чужое разобранное ТЗ")
                .allSatisfy(l -> assertThat(l.getRequiredSpec()).doesNotContain("400 определений"));
    }

    /** Однозначное имя по-прежнему спасает лот без кода — ради этого запасной матч и нужен. */
    @Test
    void uniqueNameStillRescuesLegacyLot() {
        String anno = "MERGE-UNIQ-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(
                lot(null, "Датчик ультразвуковой", "описание"),
                lot(null, "Набор реагентов", "описание")));
        em.flush();
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        for (TenderLot l : t.getLots()) l.setSourceLotCode(null);
        TenderLot parsed = t.getLots().stream()
                .filter(x -> "Датчик ультразвуковой".equals(x.getEquipName())).findFirst().orElseThrow();
        parsed.setRequiredSpec("разобранное ТЗ: диапазон 2.0-9.0 МГц, конвексный");
        parsed.setTechSpecStatus(TechSpecStatus.OK);
        em.flush();
        Long parsedId = parsed.getId();

        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(
                lot("17304732-ОИ1", "Датчик ультразвуковой", "описание"),
                lot("17304732-ОИ2", "Набор реагентов", "описание")));
        em.flush();
        em.clear();

        TenderLot kept = em.find(TenderLot.class, parsedId);
        assertThat(kept).as("лот с уникальным именем найден, а не пересоздан").isNotNull();
        assertThat(kept.getRequiredSpec()).contains("2.0-9.0 МГц");
        assertThat(kept.getEquipName()).as("ТЗ осталось на СВОЁМ лоте").isEqualTo("Датчик ультразвуковой");
        assertThat(kept.getSourceLotCode()).isEqualTo("17304732-ОИ1");
    }

    /**
     * Двухпроходность на уровне БД: входящий НОВЫЙ лот с тем же именем не должен уводить строку,
     * которую заберёт по коду другой входящий лот, — иначе разобранное ТЗ переезжает на чужой лот.
     */
    @Test
    void codePassRunsBeforeNamePass_soSpecStaysOnItsOwnLot() {
        String anno = "MERGE-TWOPASS-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("17300000-ОИ7", "Датчик ультразвуковой", "описание")));
        em.flush();
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        TenderLot parsed = t.getLots().get(0);
        parsed.setRequiredSpec("разобранное ТЗ: диапазон 2.0-9.0 МГц");
        parsed.setTechSpecStatus(TechSpecStatus.OK);
        em.flush();
        Long parsedId = parsed.getId();

        // новый лот с ТЕМ ЖЕ именем идёт ПЕРВЫМ, старый — вторым, по своему коду
        goszakupWriter.upsertOne(trdBuy(anno), null, List.of(
                lot("17300000-ОИ9", "Датчик ультразвуковой", "описание"),
                lot("17300000-ОИ7", "Датчик ультразвуковой", "описание")));
        em.flush();
        em.clear();

        TenderLot kept = em.find(TenderLot.class, parsedId);
        assertThat(kept).isNotNull();
        assertThat(kept.getSourceLotCode()).as("ТЗ осталось на лоте со своим кодом").isEqualTo("17300000-ОИ7");
        assertThat(kept.getRequiredSpec()).contains("2.0-9.0 МГц");

        TenderLot fresh = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().stream()
                .filter(x -> "17300000-ОИ9".equals(x.getSourceLotCode())).findFirst().orElseThrow();
        assertThat(fresh.getRequiredSpec()).as("новый лот не унаследовал чужое ТЗ").doesNotContain("2.0-9.0 МГц");
        assertThat(fresh.getTechSpecStatus()).isEqualTo(TechSpecStatus.PENDING);
    }

    /**
     * ТЗ, вписанное оператором руками через {@code PUT /api/lots/{id}}, — такое же разобранное ТЗ.
     * Без отметки {@code OK} переимпорт затирал его описанием с площадки: та же дыра, что
     * закрывалась в {@code TechSpecWriter}, но вторым входом в лот.
     */
    @Test
    void handTypedSpecIsProtectedFromReimport() {
        String anno = "MERGE-HANDSPEC-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("17300001-ОИ1", "Центрифуга", "короткое описание с площадки")));
        em.flush();
        TenderLot l = tenderRepository.findBySourceExtId(anno).orElseThrow().getLots().get(0);

        TenderLotRequest req = new TenderLotRequest();
        req.setEquipName("Центрифуга");
        req.setRequiredSpec("вписано руками: центрифуга лабораторная, 15000 об/мин, ротор угловой");
        lotController.update(l.getId(), req);
        em.flush();

        assertThat(em.find(TenderLot.class, l.getId()).getTechSpecStatus())
                .as("ручное ТЗ помечается разобранным").isEqualTo(TechSpecStatus.OK);

        goszakupWriter.upsertOne(trdBuy(anno), null,
                List.of(lot("17300001-ОИ1", "Центрифуга", "короткое описание с площадки")));
        em.flush();
        em.clear();

        assertThat(em.find(TenderLot.class, l.getId()).getRequiredSpec())
                .as("ручное ТЗ не затёрто описанием площадки").contains("15000 об/мин");
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

    /** Правило «забрать можно один раз» должно держаться и на стороне СК-Ф, а не только goszakup. */
    @Test
    void skDuplicateLotCodesDoNotCollapseIntoOneRow() {
        String anno = "MERGE-SKDUP-" + System.nanoTime();
        skWriter.upsert(announce(anno), List.of(
                new SkLot("A-Т1", "Томограф компьютерный", new BigDecimal("500000"), 1)), null);
        em.flush();

        skWriter.upsert(announce(anno), List.of(
                new SkLot("A-Т1", "Томограф компьютерный", new BigDecimal("500000"), 1),
                new SkLot("A-Т1", "Аппарат МРТ", new BigDecimal("700000"), 1)), null);
        em.flush();
        em.clear();

        assertThat(lotCount(anno)).isEqualTo(2L);
    }

    /**
     * Пустой разбор lots-таблицы СК-Ф (смена вёрстки ЦЭФ / страница ошибки / троттлинг) не должен
     * читаться как «лотов больше нет»: скрейп хрупок, а ценой была бы вся работа оператора.
     */
    @Test
    void skEmptyLotListDoesNotWipeExistingLots() {
        String anno = "MERGE-SKEMPTY-" + System.nanoTime();
        skWriter.upsert(announce(anno), List.of(
                new SkLot("A-Т1", "Томограф компьютерный", new BigDecimal("500000"), 1)), null);
        em.flush();

        assertThatThrownBy(() -> skWriter.upsert(announce(anno), List.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 лотов");

        assertThat(lotCount(anno)).isEqualTo(1L);
    }
}
