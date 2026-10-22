package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Итог лота на площадке (спека 2026-10-22-tender-results-market-prices §4, §12): строка на лот, каскадом от лота. */
@Entity
@Table(name = "tender_lot_result")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderLotResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_lot_id", nullable = false, unique = true)
    private Long tenderLotId;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "lot_status_id")
    private Integer lotStatusId;

    @Column(name = "lot_status", length = 100)
    private String lotStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private LotOutcome outcome = LotOutcome.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "fail_reason", length = 12)
    private FailReason failReason;

    @Column(name = "fail_text", length = 300)
    private String failText;

    /** Публикация итогов — время площадки (UTC+5), без пояса. */
    @Column(name = "results_at")
    private LocalDateTime resultsAt;

    @Column(name = "protocol_url", length = 300)
    private String protocolUrl;

    @Column(name = "protocol_name", length = 200)
    private String protocolName;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_status", length = 16)
    private ProtocolStatus protocolStatus;

    @Column(name = "protocol_note", length = 300)
    private String protocolNote;

    @Column(name = "protocol_attempts", nullable = false)
    @Builder.Default
    private int protocolAttempts = 0;

    @Column(name = "bids_count")
    private Integer bidsCount;

    @Column(name = "rejected_count")
    private Integer rejectedCount;

    @Column(name = "winner_name", length = 300)
    private String winnerName;

    @Column(name = "winner_bin", length = 20)
    private String winnerBin;

    @Column(name = "winner_price", precision = 15, scale = 2)
    private BigDecimal winnerPrice;

    @Column(name = "second_name", length = 300)
    private String secondName;

    @Column(name = "second_bin", length = 20)
    private String secondBin;

    @Column(name = "second_price", precision = 15, scale = 2)
    private BigDecimal secondPrice;

    @Column(name = "min_price", precision = 15, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", precision = 15, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "our_place")
    private Integer ourPlace;

    @Column(name = "our_price", precision = 15, scale = 2)
    private BigDecimal ourPrice;

    @Column(name = "our_rejected")
    private Boolean ourRejected;

    @Column(name = "contract_number", length = 60)
    private String contractNumber;

    @Column(name = "contract_signed_at")
    private LocalDate contractSignedAt;

    @Column(name = "contract_unit_price", precision = 15, scale = 2)
    private BigDecimal contractUnitPrice;

    @Column(name = "contract_sum", precision = 17, scale = 2)
    private BigDecimal contractSum;

    @Column(name = "contract_supplier_bin", length = 20)
    private String contractSupplierBin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<ResultAttempt> attempts = new ArrayList<>();

    @Column(name = "next_tender_id")
    private Long nextTenderId;

    /** Событие итога заведено (сигнал / сводка) — второй раз не заводится. */
    @Column(name = "event_at")
    private OffsetDateTime eventAt;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

    @Column(name = "final_at")
    private OffsetDateTime finalAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
