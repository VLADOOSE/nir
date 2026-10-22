package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.deal.TenderProgressBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/** Этапы и «Дальше» карточки тендера (спека 2026-10-18 §4.1) — по строкам таблицы. */
@SpringBootTest
@Transactional
class TenderProgressTest {

    @Autowired TenderProgressBuilder builder;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired ActivityApplyRepository applies;
    @Autowired EntityManager em;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Импортный тендер с 2 лотами (у первого ТЗ разобрано), приём до 21.10 10:00. */
    private Tender tender(boolean imported) {
        Tender t = Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE")
                .platform(imported ? TenderPlatform.GOSZAKUP : null)
                .deadline(LocalDate.of(2026, 10, 21)).deadlineAt(LocalDateTime.of(2026, 10, 21, 10, 0)).build();
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(1).equipName("ZZ Лот 1").quantity(1)
                .techSpecStatus(TechSpecStatus.OK).build());
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(2).equipName("ZZ Лот 2").quantity(1).build());
        return tenders.save(t);
    }

    private PriceRequest ask(Tender t, String status, String price) {
        Distributor d = distributors.save(Distributor.builder().name("ZZ Т " + System.nanoTime()).build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status(status).build();
        pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(0)).requestedQuantity(1)
                .responsePrice(price == null ? null : new BigDecimal(price)).build());
        return priceRequests.save(pr);
    }

    private ActivityApply apply(Tender t, String status) {
        return applies.save(ActivityApply.builder().tender(t).status(status).build());
    }

    private DealProgress p(Tender t, LocalDateTime now) {
        em.flush();
        return builder.build(tenders.findById(t.getId()).orElseThrow(), now);
    }

    private static String states(DealProgress p) {
        return p.steps().stream().map(s -> s.key() + ":" + s.state()).collect(joining(" "));
    }

    @Test
    void importedWithoutTzAsksToParse() {
        DealProgress p = p(tender(true), NOW);
        assertThat(p.steps().get(0).detail()).isEqualTo("ТЗ: 1 из 2");
        assertThat(states(p)).startsWith("LOTS:CURRENT PRICES:TODO");
        assertThat(p.next()).isEqualTo(new Next("Разберите ТЗ: 1 лот без ТЗ", "TO_LOTS", null));
    }

    @Test
    void manualAsksForPrices() {
        DealProgress p = p(tender(false), NOW);
        assertThat(p.steps().get(0).detail()).isEqualTo("2 лота");
        assertThat(p.next()).isEqualTo(new Next("Запросите цены по лотам", "TO_LOTS", null));
    }

    @Test
    void waitingThenPriced() {
        Tender t = tender(false);
        ask(t, "SENT", null);
        ask(t, "RESPONDED", null);
        assertThat(p(t, NOW).next()).isEqualTo(new Next("Ответил 1 из 2", "OPEN_REQUESTS", null));
        ask(t, "RESPONDED", "500.00");
        DealProgress p = p(t, NOW);
        assertThat(p.steps().get(1).detail()).isEqualTo("цены по 1 из 2");
        assertThat(p.next()).isEqualTo(new Next("Цены есть — выберите победителей и соберите заявку", "OPEN_REQUESTS", null));
    }

    @Test
    void draftApplyBeforeAndAfterDeadline() {
        Tender t = tender(false);
        ActivityApply a = apply(t, "DRAFT");
        DealProgress p = p(t, NOW);
        assertThat(states(p)).isEqualTo("LOTS:DONE PRICES:DONE APPLY:CURRENT RESULT:TODO CONTRACT:TODO DELIVERY:TODO PAYMENT:TODO");
        assertThat(p.steps().get(2).detail()).isEqualTo("№ " + a.getId() + " · черновик");
        assertThat(p.next()).isEqualTo(new Next("Подайте заявку — приём до 21.10 10:00", "OPEN_APPLY", a.getId()));
        DealProgress late = p(t, LocalDateTime.of(2026, 10, 21, 10, 0));
        assertThat(late.next()).isEqualTo(new Next("Приём закрыт 21.10 10:00 — заявку не подали", null, null));
        assertThat(states(late)).endsWith("RESULT:SKIPPED CONTRACT:SKIPPED DELIVERY:SKIPPED PAYMENT:SKIPPED");
    }

    @Test
    void dateOnlyDeadlineStillOpenToday() {
        Tender t = tender(false);
        t.setDeadlineAt(null);
        apply(t, "DRAFT");
        assertThat(p(t, LocalDateTime.of(2026, 10, 21, 18, 0)).next().text()).isEqualTo("Подайте заявку — приём до 21.10");
        assertThat(p(t, LocalDateTime.of(2026, 10, 22, 0, 1)).next().text()).isEqualTo("Приём закрыт 21.10 — заявку не подали");
    }

    @Test
    void submittedWaitsForResult() {
        Tender t = tender(false);
        apply(t, "SUBMITTED");
        DealProgress p = p(t, NOW);
        assertThat(p.steps().get(3)).satisfies(s -> {
            assertThat(s.state()).isEqualTo("CURRENT");
            assertThat(s.detail()).isEqualTo("на рассмотрении");
        });
        assertThat(p.next()).isEqualTo(new Next("Ждём итога", null, null));
    }

    @Test
    void wonThenContractDeliveryPayment() {
        Tender t = tender(false);
        ActivityApply a = apply(t, "WON");
        assertThat(p(t, NOW).next()).isEqualTo(new Next("Выиграли — заключите договор, затем поставка и оплата", "OPEN_APPLY", a.getId()));
        a.setContractNumber("15");
        a.setContractSignedAt(LocalDate.of(2026, 10, 25));
        a.setDeliveredAt(LocalDate.of(2026, 11, 5));
        a.setPaidAt(LocalDate.of(2026, 11, 10));
        DealProgress p = p(t, NOW);
        assertThat(states(p)).isEqualTo("LOTS:DONE PRICES:DONE APPLY:DONE RESULT:DONE CONTRACT:DONE DELIVERY:DONE PAYMENT:DONE");
        assertThat(p.steps().get(4).detail()).isEqualTo("№ 15 от 25.10");
        assertThat(p.next()).isEqualTo(new Next("Сделка закрыта: оплачено 10.11", null, null));
    }

    @Test
    void lostAndCancelledSkipTheTail() {
        Tender t = tender(false);
        apply(t, "LOST");
        DealProgress p = p(t, NOW);
        assertThat(states(p)).isEqualTo("LOTS:DONE PRICES:DONE APPLY:DONE RESULT:DONE CONTRACT:SKIPPED DELIVERY:SKIPPED PAYMENT:SKIPPED");
        assertThat(p.steps().get(3).detail()).isEqualTo("проиграли");
        assertThat(p.next()).isEqualTo(new Next("Тендер проигран", null, null));

        Tender c = tender(false);
        c.setStatus("CANCELLED");
        DealProgress pc = p(c, NOW);
        assertThat(pc.steps().get(3).detail()).isEqualTo("тендер отменён");
        assertThat(pc.next()).isEqualTo(new Next("Тендер отменён на площадке", null, null));
    }

    /** Отменённый площадкой тендер без заявки: «✓» — только у сделанного, несделанное до итога — «–», а не «✓ не запрашивали». */
    @Test
    void cancelledWithoutApplyTicksOnlyWhatWasDone() {
        Tender idle = tender(false);
        idle.setStatus("CANCELLED");
        assertThat(states(p(idle, NOW)))
                .isEqualTo("LOTS:SKIPPED PRICES:SKIPPED APPLY:SKIPPED RESULT:DONE CONTRACT:SKIPPED DELIVERY:SKIPPED PAYMENT:SKIPPED");

        Tender priced = tender(false);
        priced.setStatus("CANCELLED");
        ask(priced, "RESPONDED", "500.00");
        assertThat(states(p(priced, NOW)))
                .isEqualTo("LOTS:DONE PRICES:DONE APPLY:SKIPPED RESULT:DONE CONTRACT:SKIPPED DELIVERY:SKIPPED PAYMENT:SKIPPED");
    }

    /** Оплата без отметки поставки: оплаченное поставлено — сделка закрыта, а не «ждём поставку». */
    @Test
    void paidWithoutDeliveryDateIsClosed() {
        Tender t = tender(false);
        ActivityApply a = apply(t, "WON");
        a.setContractNumber("15");
        a.setPaidAt(LocalDate.of(2026, 11, 10));
        DealProgress p = p(t, NOW);
        assertThat(states(p)).isEqualTo("LOTS:DONE PRICES:DONE APPLY:DONE RESULT:DONE CONTRACT:DONE DELIVERY:DONE PAYMENT:DONE");
        assertThat(p.next()).isEqualTo(new Next("Сделка закрыта: оплачено 10.11", null, null));
    }

    /** Без нашей заявки этап «Итог» подписан итогом площадки (спека 2026-10-22-tender-results-market-prices §11). */
    @Test
    void resultStepShowsPlatformResult(@Autowired TenderLotResultRepository results) {
        Tender t = tender(true);
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).winnerName("ИП Әбдібай").winnerPrice(new BigDecimal("251000")).build());
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(1).getId()).tenderId(t.getId())
                .outcome(LotOutcome.FAILED).bidsCount(0).build());
        DealProgress p = builder.build(t, LocalDateTime.of(2026, 10, 25, 12, 0));
        assertThat(p.steps().stream().filter(s -> s.key().equals("RESULT")).findFirst().orElseThrow().detail())
                .isEqualTo("состоялся 1 из 2 · не состоялся 1");
        results.deleteAllInBatch(results.findByTenderId(t.getId()).stream().filter(r -> r.getOutcome() == LotOutcome.FAILED).toList());
        assertThat(builder.build(t, LocalDateTime.of(2026, 10, 25, 12, 0)).steps().stream().filter(s -> s.key().equals("RESULT"))
                .findFirst().orElseThrow().detail()).isEqualTo("победил ИП Әбдібай · 251\u00A0000\u00A0₸");
    }
}
