package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Пункт плана закупок goszakup с нашим видом изделия (V36, спека §4.6, §10). */
@Entity
@Table(name = "procurement_plan_point")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcurementPlanPoint implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(name = "ext_id", nullable = false, unique = true)
    private Long extId;

    @Column(nullable = false)
    private int year;

    private Integer month;

    @Column(name = "customer_bin", length = 20)
    private String customerBin;

    @Column(name = "customer_name", length = 500)
    private String customerName;

    @Column(length = 100)
    private String region;

    @Column(name = "delivery_place", columnDefinition = "TEXT")
    private String deliveryPlace;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(length = 100)
    private String unit;

    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "trade_method", length = 200)
    private String tradeMethod;

    @Column(name = "status_id")
    private Integer statusId;

    @Column(name = "status_name", length = 100)
    private String statusName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProductMatchLevel level;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "gone_at")
    private OffsetDateTime goneAt;

    @Column(name = "gone_status", length = 100)
    private String goneStatus;

    @Column(name = "digested_at")
    private OffsetDateTime digestedAt;
}
