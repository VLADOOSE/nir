package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Поставщик сделки (V34): к оплате, валюта, «Не заказываем», заказ и отгрузка. Платежи — DealPayment. */
@Entity
@Table(name = "deal_supplier")
@DynamicUpdate
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "distributor_id", nullable = false)
    private Long distributorId;

    /** К оплате; пусто — по КП (только в валюте рынка). */
    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean excluded;

    @Column(nullable = false)
    private boolean manual;

    @Column(name = "ordered_on")
    private LocalDate orderedOn;

    @Column(name = "order_note", columnDefinition = "TEXT")
    private String orderNote;

    @Column(name = "shipped_on")
    private LocalDate shippedOn;

    @Column(name = "ship_note", columnDefinition = "TEXT")
    private String shipNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
