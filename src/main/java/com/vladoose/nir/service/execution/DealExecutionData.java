package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.DealExecution;
import com.vladoose.nir.entity.DealPayment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Исполнение одной сделки (спека 2026-10-19-deal-execution §6): строка сделки, главное принятое КП, поставщики (группы КП
 * ∪ строки deal_supplier), платежи клиента и снимок для расчёта. Из него строятся и шаги, и вид раздела, и лента.
 */
public record DealExecutionData(Long tenderId, String currency, DealExecution row, MainOffer mainOffer,
                                List<OfferGroup> offerGroups, int unassignedCount, BigDecimal unassignedPurchase,
                                List<SupplierLine> supplierLines, List<DealPayment> clientPayments,
                                ExecutionSnapshot snapshot) {

    /** Главное принятое КП — последнее по id; total — его итог (у КП без цен — 0). */
    public record MainOffer(Long id, BigDecimal total, String label) {}

    /** Строки главного принятого КП одного поставщика: сумма закупок (каждая строка — до тиына), первая строка — порядок. */
    public record OfferGroup(Long distributorId, BigDecimal purchase, int firstLine) {}

    /** Поставщик раздела: строка deal_supplier (rowId) и/или группа КП; amount — к оплате (своя или по КП). */
    public record SupplierLine(Long rowId, Long distributorId, String name, boolean fromOffer, boolean manual,
                               boolean excluded, String currency, BigDecimal amount, boolean amountFromOffer,
                               BigDecimal offerAmount, LocalDate orderedOn, String orderNote, BigDecimal paid,
                               List<DealPayment> payments, LocalDate shippedOn, String shipNote) {}

    /** Раздел виден: есть принятое КП или данные исполнения. */
    public boolean visible() {
        return mainOffer != null || snapshot.hasData();
    }

    public boolean inOffer(Long distributorId) {
        return offerGroups.stream().anyMatch(g -> g.distributorId().equals(distributorId));
    }

    /** Положительное число, иначе null («КП без цен» и строки без закупки — сумма неизвестна). */
    public static BigDecimal positive(BigDecimal v) {
        return v != null && v.signum() > 0 ? v : null;
    }
}
