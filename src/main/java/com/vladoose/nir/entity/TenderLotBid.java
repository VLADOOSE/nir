package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Участник протокола итогов (спека §5): место (у отклонённых — пусто), цена за единицу и сумма, время подачи. */
@Entity
@Table(name = "tender_lot_bid")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderLotBid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "result_id", nullable = false)
    private Long resultId;

    private Integer place;

    @Column(name = "supplier_name", length = 300)
    private String supplierName;

    @Column(name = "supplier_bin", length = 20)
    private String supplierBin;

    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 17, scale = 2)
    private BigDecimal total;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(nullable = false)
    private boolean rejected;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(nullable = false)
    private boolean winner;

    @Column(name = "second_place", nullable = false)
    private boolean secondPlace;

    @Column(nullable = false)
    private boolean ours;
}
