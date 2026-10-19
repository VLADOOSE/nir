package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Исполнение сделки (V34): договор, предоплата, своя сумма, «Со склада», поставка, документы — одна строка на сделку.
 * Не рыночная: только через заявку с гардом рынка. @DynamicUpdate — две вкладки, правящие разные поля, не затирают
 * друг друга (как Tender).
 */
@Entity
@Table(name = "deal_execution")
@DynamicUpdate
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealExecution {

    @Id
    @Column(name = "tender_id")
    private Long tenderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_kind", length = 10)
    private ContractKind contractKind;

    @Column(name = "contract_number", length = 100)
    private String contractNumber;

    @Column(name = "contract_date")
    private LocalDate contractDate;

    @Column(name = "contract_note", columnDefinition = "TEXT")
    private String contractNote;

    /** Предоплата, %: 100 — всё вперёд, 0 — оплата после поставки. */
    @Column(name = "prepay_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal prepayPct = new BigDecimal("100.00");

    /** Своя сумма сделки; пусто — итог главного принятого КП. */
    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "from_stock", nullable = false)
    private boolean fromStock;

    @Column(name = "delivered_on")
    private LocalDate deliveredOn;

    @Column(name = "delivery_note", columnDefinition = "TEXT")
    private String deliveryNote;

    @Column(name = "docs_on")
    private LocalDate docsOn;

    @Column(name = "docs_note", columnDefinition = "TEXT")
    private String docsNote;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
