package com.vladoose.nir.integration;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.GoszakupTenderWriter;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.skpharmacy.SkAnnounce;
import com.vladoose.nir.integration.skpharmacy.SkLot;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyTenderWriter;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пакет 3 ревью импорта — данные лота и тендера, проверка на уровне БД (flush + clear + перечитывание):
 * B1 — цена за единицу у goszakup, B2 — время окончания приёма, B5 — лот, снятый с площадки, на который
 * уже есть запрос КП или позиция заявки, не роняет обновление тендера.
 */
@SpringBootTest
@Transactional
class ImportDataFixesTest {

    /** 2026-10-09 14:10 по Уральску (UTC+5). */
    static final Clock AT_14_10 = Clock.fixed(Instant.parse("2026-10-09T09:10:00Z"), ZoneOffset.UTC);

    @Autowired GoszakupTenderWriter goszakupWriter;
    @Autowired SkPharmacyTenderWriter skWriter;
    @Autowired TenderRepository tenderRepository;
    @Autowired RegionResolver regionResolver;
    @Autowired SourceRemovedLots removedLots;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired ActivityApplyRepository applyRepository;
    @PersistenceContext EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    // ---------- данные площадок ----------

    static TrdBuyDto trdBuy(String anno, String name, String endDate) {
        TrdBuyDto d = new TrdBuyDto();
        d.setNumberAnno(anno);
        d.setNameRu(name);
        d.setRefBuyStatusId(240);          // «Опубликовано (приём заявок)»
        d.setEndDate(endDate);
        return d;
    }

    static LotDto lot(String code, String name, String amount, String count) {
        LotDto l = new LotDto();
        l.setLotNumber(code);
        l.setNameRu(name);
        l.setAmount(amount == null ? null : new BigDecimal(amount));
        l.setCount(count == null ? null : new BigDecimal(count));
        return l;
    }

    static SkAnnounce announce(String anno, String acceptEnd) {
        return new SkAnnounce("777", anno, "ТОО «СК-Фармация»", "Закуп медицинских изделий", "Тендер", "Товар",
                "2026-10-01 09:00:00", acceptEnd, 2, new BigDecimal("1000000"), "Опубликовано");
    }

    static SkLot skLot(String code, String name) {
        return new SkLot(code, name, new BigDecimal("1500"), 4, null, null);
    }

    Tender reread(String anno) {
        em.flush();
        em.clear();
        return tenderRepository.findBySourceExtId(anno).orElseThrow();
    }

    TenderLot lotByCode(Tender t, String code) {
        return t.getLots().stream().filter(l -> code.equals(l.getSourceLotCode())).findFirst().orElseThrow();
    }

    /** Запрос КП поставщику по лоту — так лот попадает под внешний ключ price_request_item. */
    void priceRequestOn(TenderLot lot) {
        Distributor d = new Distributor();
        d.setName("ZZ Поставщик B5 " + System.nanoTime());
        distributorRepository.save(d);
        PriceRequest pr = new PriceRequest();
        pr.setTender(lot.getTender());
        pr.setDistributor(d);
        pr.setStatus("SENT");
        pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(2).build());
        priceRequestRepository.save(pr);
        em.flush();
    }

    // ---------- B1: цена за единицу ----------

    @Test
    void goszakup_maxCostIsUnitPrice_notLotSum() {
        String anno = "B1-GZ-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп перчаток", "2030-01-10 10:00:00"), null,
                List.of(lot("87197521-ОИ1", "Перчатки", "54000", "300"),
                        lot("87197521-ОИ2", "Халат", "1000", "3")));

        Tender t = reread(anno);
        assertThat(lotByCode(t, "87197521-ОИ1").getMaxCost()).isEqualByComparingTo("180.00");
        assertThat(lotByCode(t, "87197521-ОИ2").getMaxCost()).isEqualByComparingTo("333.33");
    }

    // ---------- B2: время окончания приёма ----------

    @Test
    void goszakup_deadlineTimeKept_tenderClosedByTimeNotByDate() {
        GoszakupTenderWriter writer = new GoszakupTenderWriter(tenderRepository, regionResolver, removedLots, AT_14_10);
        String closed = "B2-GZ-A-" + System.nanoTime();
        String open = "B2-GZ-B-" + System.nanoTime();

        writer.upsertOne(trdBuy(closed, "Закуп", "2026-10-09 10:25:00"), null, List.of(lot("1-ОИ1", "Пробирка", "100", "1")));
        writer.upsertOne(trdBuy(open, "Закуп", "2026-10-09 18:00:00"), null, List.of(lot("2-ОИ1", "Пробирка", "100", "1")));

        Tender a = reread(closed);
        assertThat(a.getDeadline()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(a.getDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 25));
        assertThat(a.getStatus()).as("приём закончился в 10:25, сейчас 14:10").isEqualTo("COMPLETED");
        assertThat(tenderRepository.findBySourceExtId(open).orElseThrow().getStatus())
                .as("приём до 18:00 ещё идёт").isEqualTo("ACTIVE");
    }

    @Test
    void sk_deadlineTimeKept_tenderClosedByTimeNotByDate() {
        SkPharmacyTenderWriter writer = new SkPharmacyTenderWriter(tenderRepository, regionResolver, removedLots, AT_14_10);
        String closed = "B2-SK-A-" + System.nanoTime();
        String open = "B2-SK-B-" + System.nanoTime();

        writer.upsert(announce(closed, "2026-10-09 10:25:00"), List.of(skLot("1-Т1", "Эндоскоп")), null, true);
        writer.upsert(announce(open, "2026-10-09 18:00:00"), List.of(skLot("2-Т1", "Эндоскоп")), null, true);

        Tender a = reread(closed);
        assertThat(a.getDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 25));
        assertThat(a.getStatus()).isEqualTo("COMPLETED");
        assertThat(tenderRepository.findBySourceExtId(open).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    // ---------- B5: снят с площадки, но есть работа ----------

    @Test
    void goszakup_lotWithPriceRequestGoneFromPlatform_keptAndMarked_restOfTenderUpdated() {
        String anno = "B5-GZ-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("5-ОИ1", "Центрифуга", "300000", "1"), lot("5-ОИ2", "Морозильник", "500000", "1"),
                        lot("5-ОИ3", "Штатив", "1000", "1")));
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        priceRequestOn(lotByCode(t, "5-ОИ2"));

        // переимпорт: «Морозильник» (с запросом КП) и «Штатив» (без работы) сняли с площадки,
        // название закупки поменялось, добавился новый лот
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп (изменено)", "2030-01-10 10:00:00"), null,
                List.of(lot("5-ОИ1", "Центрифуга", "300000", "1"), lot("5-ОИ4", "Термостат", "200000", "1")));

        Tender after = reread(anno);
        assertThat(after.getDescription()).as("обновление тендера не откатилось").isEqualTo("Закуп (изменено)");
        assertThat(after.getLots()).extracting(TenderLot::getSourceLotCode)
                .containsExactlyInAnyOrder("5-ОИ1", "5-ОИ2", "5-ОИ4");
        assertThat(lotByCode(after, "5-ОИ2").getSourceRemovedAt()).as("лот с запросом КП помечен «снят с площадки»").isNotNull();
        assertThat(lotByCode(after, "5-ОИ1").getSourceRemovedAt()).isNull();
        assertThat(lotByCode(after, "5-ОИ4").getSourceRemovedAt()).isNull();
    }

    @Test
    void lotReturnedToPlatform_markCleared() {
        String anno = "B5-RET-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("6-ОИ1", "Центрифуга", "300000", "1"), lot("6-ОИ2", "Морозильник", "500000", "1")));
        priceRequestOn(lotByCode(tenderRepository.findBySourceExtId(anno).orElseThrow(), "6-ОИ2"));
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("6-ОИ1", "Центрифуга", "300000", "1")));
        assertThat(lotByCode(reread(anno), "6-ОИ2").getSourceRemovedAt()).isNotNull();

        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("6-ОИ1", "Центрифуга", "300000", "1"), lot("6-ОИ2", "Морозильник", "500000", "1")));

        assertThat(lotByCode(reread(anno), "6-ОИ2").getSourceRemovedAt()).as("лот вернулся на площадку").isNull();
    }

    @Test
    void lotInApplicationGoneFromPlatform_kept() {
        String anno = "B5-APPLY-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("7-ОИ1", "Центрифуга", "300000", "1"), lot("7-ОИ2", "Морозильник", "500000", "1")));
        Tender t = tenderRepository.findBySourceExtId(anno).orElseThrow();
        ActivityApply apply = new ActivityApply();
        apply.setTender(t);
        apply.setStatus("DRAFT");
        apply.getItems().add(ApplyItem.builder().apply(apply).tenderLot(lotByCode(t, "7-ОИ2")).quantity(1).build());
        applyRepository.save(apply);
        em.flush();

        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("7-ОИ1", "Центрифуга", "300000", "1")));

        assertThat(lotByCode(reread(anno), "7-ОИ2").getSourceRemovedAt()).isNotNull();
    }

    /**
     * Ревью ветки, п. 2: снятый лот возвращается только по КОДУ. По имени его забрал бы новый лот площадки
     * с тем же названием (другой код, другое количество) — запрос КП молча переехал бы на чужой лот.
     */
    @Test
    void removedLotIsNotReclaimedByNameByAnotherPlatformLot() {
        String anno = "B5-NAME-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("9-ОИ1", "Перчатки нитриловые", "30000", "300"), lot("9-ОИ2", "Халат", "1000", "1")));
        priceRequestOn(lotByCode(tenderRepository.findBySourceExtId(anno).orElseThrow(), "9-ОИ1"));
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("9-ОИ2", "Халат", "1000", "1")));

        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("9-ОИ2", "Халат", "1000", "1"), lot("9-ОИ5", "Перчатки нитриловые", "100000", "1000")));

        Tender after = reread(anno);
        assertThat(after.getLots()).extracting(TenderLot::getSourceLotCode)
                .containsExactlyInAnyOrder("9-ОИ1", "9-ОИ2", "9-ОИ5");
        TenderLot removed = lotByCode(after, "9-ОИ1");
        assertThat(removed.getSourceRemovedAt()).as("снятый лот остался снятым").isNotNull();
        assertThat(removed.getQuantity()).as("количество нового лота не переехало на снятый").isEqualTo(300);
        assertThat(lotByCode(after, "9-ОИ5").getSourceRemovedAt()).isNull();
    }

    /** Отметка — момент ПЕРВОГО снятия: следующие прогоны без лота её не двигают. */
    @Test
    void removedMarkIsSetOnce() {
        Clock first = Clock.fixed(Instant.parse("2026-10-09T09:10:00Z"), ZoneOffset.UTC);
        Clock nextDay = Clock.fixed(Instant.parse("2026-10-10T09:10:00Z"), ZoneOffset.UTC);
        String anno = "B5-ONCE-" + System.nanoTime();
        goszakupWriter.upsertOne(trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null,
                List.of(lot("11-ОИ1", "Центрифуга", "300000", "1"), lot("11-ОИ2", "Морозильник", "500000", "1")));
        priceRequestOn(lotByCode(tenderRepository.findBySourceExtId(anno).orElseThrow(), "11-ОИ2"));

        new GoszakupTenderWriter(tenderRepository, regionResolver, removedLots, first).upsertOne(
                trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null, List.of(lot("11-ОИ1", "Центрифуга", "300000", "1")));
        new GoszakupTenderWriter(tenderRepository, regionResolver, removedLots, nextDay).upsertOne(
                trdBuy(anno, "Закуп", "2030-01-10 10:00:00"), null, List.of(lot("11-ОИ1", "Центрифуга", "300000", "1")));

        assertThat(lotByCode(reread(anno), "11-ОИ2").getSourceRemovedAt().toInstant())
                .isEqualTo(Instant.parse("2026-10-09T09:10:00Z"));
    }

    /** СК-Фармация: номер лота — позиция в списке площадки; оставленному — номер после лотов площадки, без дублей. */
    @Test
    void sk_keptLotNumberedAfterPlatformLots() {
        String anno = "B5-SKNUM-" + System.nanoTime();
        skWriter.upsert(announce(anno, "2030-01-10 10:00:00"),
                List.of(skLot("10-Т1", "Эндоскоп"), skLot("10-Т2", "Колоноскоп"), skLot("10-Т3", "Бронхоскоп")), null, true);
        priceRequestOn(lotByCode(tenderRepository.findBySourceExtId(anno).orElseThrow(), "10-Т2"));

        skWriter.upsert(announce(anno, "2030-01-10 10:00:00"),
                List.of(skLot("10-Т1", "Эндоскоп"), skLot("10-Т3", "Бронхоскоп")), null, true);

        Tender after = reread(anno);
        assertThat(after.getLots()).extracting(TenderLot::getLotNumber).doesNotHaveDuplicates();
        assertThat(lotByCode(after, "10-Т2").getLotNumber()).isEqualTo(3);
    }

    /** Неполный список СК-Фармации (обрыв пагинации): недочитанные лоты не сняты — отметки нет. */
    @Test
    void sk_incompleteList_marksNothing() {
        String anno = "B5-SKINC-" + System.nanoTime();
        skWriter.upsert(announce(anno, "2030-01-10 10:00:00"),
                List.of(skLot("12-Т1", "Эндоскоп"), skLot("12-Т2", "Колоноскоп")), null, true);
        priceRequestOn(lotByCode(tenderRepository.findBySourceExtId(anno).orElseThrow(), "12-Т2"));

        skWriter.upsert(announce(anno, "2030-01-10 10:00:00"), List.of(skLot("12-Т1", "Эндоскоп")), null, false);

        assertThat(lotByCode(reread(anno), "12-Т2").getSourceRemovedAt()).isNull();
    }

    @Test
    void sk_lotWithPriceRequestGoneFromPlatform_keptAndMarked() {
        String anno = "B5-SK-" + System.nanoTime();
        skWriter.upsert(announce(anno, "2030-01-10 10:00:00"),
                List.of(skLot("8-Т1", "Эндоскоп"), skLot("8-Т2", "Видеоэндоскопическая система")), null, true);
        priceRequestOn(lotByCode(tenderRepository.findBySourceExtId(anno).orElseThrow(), "8-Т2"));

        skWriter.upsert(announce(anno, "2030-01-10 10:00:00"), List.of(skLot("8-Т1", "Эндоскоп")), null, true);

        Tender after = reread(anno);
        assertThat(after.getLots()).hasSize(2);
        assertThat(lotByCode(after, "8-Т2").getSourceRemovedAt()).isNotNull();
        assertThat(lotByCode(after, "8-Т1").getSourceRemovedAt()).isNull();
    }
}
