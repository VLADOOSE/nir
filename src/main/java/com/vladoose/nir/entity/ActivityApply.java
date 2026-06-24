package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Entity
@Table(name = "activity_apply")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityApply implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_id", nullable = false)
    private Tender tender;

    @Column(length = 50)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "contract_number", length = 100)
    private String contractNumber;

    @Column(name = "contract_signed_at")
    private LocalDate contractSignedAt;

    /**
     * Статус поставки внутри WON. Значения:
     * NONE — поставка ещё не начата, ORDERED — оборудование заказано у дистрибьютора,
     * DELIVERED — доставлено заказчику, PAID — оплачено заказчиком.
     */
    @Column(name = "delivery_status", length = 50)
    @Builder.Default
    private String deliveryStatus = "NONE";

    @Column(name = "delivered_at")
    private LocalDate deliveredAt;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @OneToMany(mappedBy = "apply", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ApplyItem> items = new ArrayList<>();
}
