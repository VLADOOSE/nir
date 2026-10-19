package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.ContractKind;
import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Снимок исполнения сделки — вход расчёта этапов (спека 2026-10-19-deal-execution §6). amount — сумма сделки (своя или
 * итог главного принятого КП), null — неизвестна; суммы клиента — в валюте рынка, поставщика — в его валюте.
 */
@Builder(toBuilder = true)
public record ExecutionSnapshot(String currency, String offerLabel, BigDecimal amount, ContractKind contractKind,
                                String contractNumber, LocalDate contractDate, BigDecimal prepayPct,
                                BigDecimal clientPaid, LocalDate lastClientPaidOn, boolean fromStock,
                                List<SupplierState> suppliers, LocalDate deliveredOn, LocalDate docsOn,
                                boolean hasData) {

    static final BigDecimal HUNDRED = new BigDecimal("100");

    public ExecutionSnapshot {
        if (prepayPct == null) prepayPct = HUNDRED;
        if (clientPaid == null) clientPaid = BigDecimal.ZERO;
        suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
    }

    /** Поставщик сделки: amount — к оплате (своя или по КП), null — неизвестна; paid — оплачено ему. */
    @Builder(toBuilder = true)
    public record SupplierState(Long distributorId, String name, boolean excluded, String currency, BigDecimal amount,
                                LocalDate orderedOn, BigDecimal paid, LocalDate shippedOn) {
        public SupplierState {
            if (paid == null) paid = BigDecimal.ZERO;
        }

        public boolean amountKnown() { return amount != null && amount.signum() > 0; }
        public boolean paidUp() { return amountKnown() && paid.compareTo(amount) >= 0; }
        public boolean done() { return orderedOn != null && paidUp() && shippedOn != null; }
    }

    public boolean amountKnown() { return amount != null && amount.signum() > 0; }

    public boolean contractSkipped() { return contractKind == ContractKind.NONE; }

    /** Вид не «без договора» и есть № или дата; вид пуст при № или дате — считается «Договор» (спека §6.3). */
    public boolean contractDone() {
        return !contractSkipped() && ((contractNumber != null && !contractNumber.isBlank()) || contractDate != null);
    }

    /** Предоплата = сумма сделки × процент, до тиына; сумма неизвестна — null. */
    public BigDecimal prepayAmount() {
        return amountKnown() ? amount.multiply(prepayPct).divide(HUNDRED, 2, RoundingMode.HALF_UP) : null;
    }

    public boolean paymentDone() { return amountKnown() && clientPaid.compareTo(amount) >= 0; }

    /** Поставщики, которые считаются: «Не заказываем» — нет. */
    public List<SupplierState> counted() { return suppliers.stream().filter(s -> !s.excluded()).toList(); }

    public boolean suppliersDone() {
        List<SupplierState> c = counted();
        return !fromStock && !c.isEmpty() && c.stream().allMatch(SupplierState::done);
    }

    public boolean deliveryDone() { return deliveredOn != null; }

    public boolean docsDone() { return docsOn != null; }
}
