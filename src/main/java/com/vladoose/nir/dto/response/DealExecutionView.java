package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Раздел «Исполнение сделки» в виде заявки (спека 2026-10-19-deal-execution §9); amount — своя или по КП, null — неизвестна. */
public record DealExecutionView(boolean visible, String currency, BigDecimal amount, boolean amountFromOffer,
                                BigDecimal offerAmount, Long offerId, String offerLabel,
                                String contractKind, String contractNumber, LocalDate contractDate, String contractNote,
                                BigDecimal prepayPct, BigDecimal prepayAmount, BigDecimal clientPaid,
                                List<Payment> clientPayments, boolean fromStock, List<Supplier> suppliers,
                                Unassigned unassigned, LocalDate deliveredOn, String deliveryNote,
                                LocalDate docsOn, String docsNote, List<FileRef> files) {

    public record Payment(Long id, BigDecimal amount, LocalDate paidOn, String note, String createdBy) {}

    /** id — строка deal_supplier (пусто — поставщик из КП ещё без отметок). */
    public record Supplier(Long id, Long distributorId, String name, boolean fromOffer, boolean manual, boolean excluded,
                           String currency, BigDecimal amount, boolean amountFromOffer, BigDecimal offerAmount,
                           LocalDate orderedOn, String orderNote, BigDecimal paid, List<Payment> payments,
                           LocalDate shippedOn, String shipNote) {}

    /** Строки КП без поставщика: сколько и сумма их закупок. */
    public record Unassigned(int count, BigDecimal purchase) {}

    /** distributorId — у файлов поставщика (из deal_supplier_id). */
    public record FileRef(Long id, String stage, Long paymentId, Long distributorId, String name, long size,
                          String uploadedBy, OffsetDateTime uploadedAt) {}
}
