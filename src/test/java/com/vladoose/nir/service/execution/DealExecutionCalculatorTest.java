package com.vladoose.nir.service.execution;

import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.DealProgress.Step;
import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.service.execution.DealExecutionCalculator.Mode;
import com.vladoose.nir.service.execution.DealExecutionCalculator.Result;
import com.vladoose.nir.service.execution.ExecutionSnapshot.SupplierState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/** Шаги исполнения и «Дальше» (спека 2026-10-19-deal-execution §3) — по таблице §3.2. */
class DealExecutionCalculatorTest {

    static final LocalDate D20 = LocalDate.of(2026, 10, 20);
    static final LocalDate D21 = LocalDate.of(2026, 10, 21);
    static final LocalDate D25 = LocalDate.of(2026, 10, 25);

    /** Пробелы в суммах и процентах — неразрывные, как в ExecutionTexts. */
    static String nb(String s) { return s.replace(' ', ' '); }

    static SupplierState.SupplierStateBuilder fotek() {
        return SupplierState.builder().distributorId(1L).name("ФОТЕК").currency("KZT").amount(new BigDecimal("1662150.00"));
    }

    static SupplierState.SupplierStateBuilder medsyst() {
        return SupplierState.builder().distributorId(2L).name("MEDSYST").currency("KZT").amount(new BigDecimal("114000.00"));
    }

    static SupplierState done(SupplierState.SupplierStateBuilder b) {
        SupplierState s = b.build();
        return s.toBuilder().orderedOn(D21).paid(s.amount()).shippedOn(D21).build();
    }

    static ExecutionSnapshot.ExecutionSnapshotBuilder accepted() {
        return ExecutionSnapshot.builder().currency("KZT").offerLabel("КП № 481").amount(new BigDecimal("3070455.00"))
                .suppliers(List.of(fotek().build(), medsyst().build()));
    }

    static ExecutionSnapshot.ExecutionSnapshotBuilder contract(ExecutionSnapshot.ExecutionSnapshotBuilder b) {
        return b.contractKind(ContractKind.CONTRACT).contractNumber("42/2026").contractDate(D20).hasData(true);
    }

    static String states(Result r) {
        return r.steps().stream().map(x -> x.key() + ":" + x.state()).collect(joining(" "));
    }

    static Result active(ExecutionSnapshot s) { return DealExecutionCalculator.evaluate(s, Mode.ACTIVE); }

    @Test
    void acceptedOffer_asksForContract() {
        Result r = active(accepted().build());
        assertThat(r.next()).isEqualTo(new Next("Клиент согласился на КП № 481 — договор или счёт", "EXEC_CONTRACT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_CONTRACT");
        assertThat(r.stageText()).isEqualTo("Договор");
        assertThat(states(r)).isEqualTo("CONTRACT:CURRENT PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO");
        assertThat(r.steps().get(1).detail()).isEqualTo("оплачено 0 из " + nb("3 070 455 тг"));
        assertThat(r.steps().get(2).detail()).isEqualTo("заказ 0/2 · оплата 0/2 · отгрузка 0/2");
        assertThat(r.allDone()).isFalse();
    }

    @Test
    void prepayment50_waitsForPrepayment() {
        Result r = active(contract(accepted()).prepayPct(new BigDecimal("50")).build());
        assertThat(r.steps().get(0)).isEqualTo(new Step("CONTRACT", "DONE", "Договор", "№ 42/2026 от 20.10"));
        assertThat(r.next()).isEqualTo(new Next("Ждём предоплату " + nb("50 %") + ": оплачено 0 из " + nb("1 535 227,50 тг"),
                "EXEC_PAY_CLIENT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_PREPAY");
        assertThat(r.stageText()).isEqualTo("Ждём предоплату");
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:CURRENT SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO");
    }

    @Test
    void prepayment100_saysFullPayment() {
        Result r = active(contract(accepted()).build());
        assertThat(r.next().text()).isEqualTo("Ждём оплату: оплачено 0 из " + nb("3 070 455 тг"));
        assertThat(r.stageText()).isEqualTo("Ждём оплату");
    }

    @Test
    void prepaymentReached_movesToSuppliers() {
        Result r = active(contract(accepted()).prepayPct(new BigDecimal("50")).clientPaid(new BigDecimal("1535227.50")).build());
        assertThat(r.next()).isEqualTo(new Next("Закажите у ФОТЕК (и ещё у 1)", "EXEC_ORDER", 1L));
        assertThat(r.stageCode()).isEqualTo("EXEC_ORDER");
        assertThat(r.steps().get(1).state()).isEqualTo("TODO");
        assertThat(r.steps().get(1).detail()).isEqualTo("оплачено " + nb("1 535 227,50") + " из " + nb("3 070 455 тг"));
    }

    @Test
    void postpayment_suppliersFirst_paymentAtTheEnd() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).prepayPct(BigDecimal.ZERO);
        assertThat(active(b.build()).next().action()).isEqualTo("EXEC_ORDER");
        Result r = active(b.suppliers(List.of(done(fotek()), done(medsyst()))).deliveredOn(D25).docsOn(D25).build());
        assertThat(r.next()).isEqualTo(new Next("Ждём оплату: оплачено 0 из " + nb("3 070 455 тг"), "EXEC_PAY_CLIENT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_FINAL_PAY");
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:CURRENT SUPPLIERS:DONE DELIVERY:DONE DOCS:DONE");
    }

    @Test
    void paymentBeforeContract_paymentDoneContractCurrent() {
        Result r = active(accepted().clientPaid(new BigDecimal("3070455.00")).lastClientPaidOn(D21).hasData(true).build());
        assertThat(states(r)).startsWith("CONTRACT:CURRENT PAYMENT:DONE");
        assertThat(r.steps().get(1).detail()).isEqualTo("оплачено полностью 21.10");
    }

    @Test
    void overpayment_isDone() {
        Result r = active(contract(accepted()).clientPaid(new BigDecimal("3100000")).lastClientPaidOn(D21).build());
        assertThat(r.steps().get(1).state()).isEqualTo("DONE");
        assertThat(r.next().action()).isEqualTo("EXEC_ORDER");
    }

    @Test
    void suppliers_amountThenPaymentThenShipment() {
        SupplierState rub = SupplierState.builder().distributorId(1L).name("ФОТЕК").currency("RUB").orderedOn(D21).build();
        SupplierState ok = done(medsyst());
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).prepayPct(BigDecimal.ZERO);

        Result r = active(b.suppliers(List.of(rub, ok)).build());
        assertThat(r.next()).isEqualTo(new Next("Впишите сумму к оплате ФОТЕК", "EXEC_SUPPLIER_AMOUNT", 1L));
        assertThat(r.stageCode()).isEqualTo("EXEC_PAY_SUPPLIERS");

        SupplierState priced = rub.toBuilder().amount(new BigDecimal("277025")).build();
        r = active(b.suppliers(List.of(priced, ok)).build());
        assertThat(r.next()).isEqualTo(new Next("Оплатите ФОТЕК: 0 из " + nb("277 025 руб."), "EXEC_PAY_SUPPLIER", 1L));

        SupplierState paid = priced.toBuilder().paid(new BigDecimal("277025")).build();
        SupplierState notShipped = ok.toBuilder().shippedOn(null).build();
        r = active(b.suppliers(List.of(paid, notShipped)).build());
        assertThat(r.next()).isEqualTo(new Next("Ждём отгрузку от ФОТЕК (и ещё от 1)", "EXEC_SHIP", 1L));
        assertThat(r.stageCode()).isEqualTo("EXEC_SHIPPING");
        assertThat(r.steps().get(2).detail()).isEqualTo("заказ 2/2 · оплата 2/2 · отгрузка 0/2");
    }

    @Test
    void noSuppliers_askToAddOrStock_stockSkipsStep() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).prepayPct(BigDecimal.ZERO).suppliers(List.of());
        assertThat(active(b.build()).next()).isEqualTo(new Next("Добавьте поставщика или отметьте «Со склада»", "EXEC_SUPPLIERS", null));
        Result r = active(b.fromStock(true).build());
        assertThat(r.steps().get(2)).isEqualTo(new Step("SUPPLIERS", "SKIPPED", "Поставщики", "со склада"));
        assertThat(r.next()).isEqualTo(new Next("Поставьте клиенту", "EXEC_DELIVERY", null));
    }

    @Test
    void excludedSupplierNotCounted() {
        Result r = active(contract(accepted()).prepayPct(BigDecimal.ZERO)
                .suppliers(List.of(fotek().excluded(true).build(), done(medsyst()))).build());
        assertThat(r.steps().get(2)).isEqualTo(new Step("SUPPLIERS", "DONE", "Поставщики", "заказ 1/1 · оплата 1/1 · отгрузка 1/1"));
    }

    @Test
    void withoutContract_stepSkipped() {
        Result r = active(accepted().contractKind(ContractKind.NONE).hasData(true).build());
        assertThat(r.steps().get(0)).isEqualTo(new Step("CONTRACT", "SKIPPED", "Договор", "без договора"));
        assertThat(r.next().action()).isEqualTo("EXEC_PAY_CLIENT");
    }

    @Test
    void docsThenAllDone() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).clientPaid(new BigDecimal("3070455"))
                .lastClientPaidOn(D21).suppliers(List.of(done(fotek()), done(medsyst()))).deliveredOn(D25);
        assertThat(active(b.build()).next()).isEqualTo(new Next("Подпишите закрывающие документы (накладная, акт)", "EXEC_DOCS", null));
        Result r = active(b.docsOn(D25).build());
        assertThat(r.next()).isEqualTo(new Next("Всё сделано — закройте сделку", "CLOSE_WON", null));
        assertThat(r.allDone()).isTrue();
        assertThat(r.stageCode()).isEqualTo("EXEC_DONE");
        assertThat(r.stageText()).isEqualTo("Всё сделано — закрыть");
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:DONE SUPPLIERS:DONE DELIVERY:DONE DOCS:DONE");
        assertThat(r.steps().get(3).detail()).isEqualTo("поставлено 25.10");
        assertThat(r.steps().get(4).detail()).isEqualTo("подписаны 25.10");
    }

    @Test
    void unknownAmount_asksForAmount() {
        Result r = active(contract(accepted()).amount(null).build());
        assertThat(r.next()).isEqualTo(new Next("Укажите сумму сделки", "EXEC_AMOUNT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_AMOUNT");
        assertThat(r.stageText()).isEqualTo("Сумма сделки?");
        assertThat(active(contract(accepted()).amount(BigDecimal.ZERO).build()).next().action()).isEqualTo("EXEC_AMOUNT");
    }

    @Test
    void closedWithoutData_allSkippedNoDetails() {
        Result r = DealExecutionCalculator.evaluate(accepted().build(), Mode.CLOSED);
        assertThat(states(r)).isEqualTo("CONTRACT:SKIPPED PAYMENT:SKIPPED SUPPLIERS:SKIPPED DELIVERY:SKIPPED DOCS:SKIPPED");
        assertThat(r.steps()).allMatch(x -> x.detail() == null);
        assertThat(r.next()).isNull();
        assertThat(r.stageCode()).isNull();
    }

    @Test
    void closedWithData_doneStaysDone() {
        Result r = DealExecutionCalculator.evaluate(contract(accepted()).deliveredOn(D25).build(), Mode.CLOSED);
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:SKIPPED SUPPLIERS:SKIPPED DELIVERY:DONE DOCS:SKIPPED");
    }

    @Test
    void passive_byDataWithoutNext() {
        Result r = DealExecutionCalculator.evaluate(ExecutionSnapshot.builder().currency("KZT").build(), Mode.PASSIVE);
        assertThat(states(r)).isEqualTo("CONTRACT:TODO PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO");
        assertThat(r.next()).isNull();
        r = DealExecutionCalculator.evaluate(ExecutionSnapshot.builder().currency("KZT").deliveredOn(D25).hasData(true).build(), Mode.PASSIVE);
        assertThat(r.steps().get(3).state()).isEqualTo("DONE");
    }

    @Test
    void contractDetails() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = accepted().hasData(true);
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(ContractKind.INVOICE).contractNumber("15").contractDate(D20).build()))
                .isEqualTo("счёт № 15 от 20.10");
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(ContractKind.INVOICE).contractNumber(null).contractDate(D20).build()))
                .isEqualTo("счёт от 20.10");
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(ContractKind.CONTRACT).contractNumber(null).contractDate(D20).build()))
                .isEqualTo("подписан 20.10");
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(null).contractNumber("42").contractDate(null).build()))
                .isEqualTo("№ 42");
    }

    @Test
    void texts() {
        assertThat(ExecutionTexts.money(new BigDecimal("1535227.50"), "KZT")).isEqualTo(nb("1 535 227,50 тг"));
        assertThat(ExecutionTexts.money(new BigDecimal("3000000.00"), "RUB")).isEqualTo(nb("3 000 000 руб."));
        assertThat(ExecutionTexts.money(new BigDecimal("1200"), "CNY")).isEqualTo(nb("1 200 юань"));
        assertThat(ExecutionTexts.pct(new BigDecimal("12.50"))).isEqualTo(nb("12,5 %"));
        assertThat(ExecutionTexts.pct(new BigDecimal("100.00"))).isEqualTo(nb("100 %"));
    }
}
