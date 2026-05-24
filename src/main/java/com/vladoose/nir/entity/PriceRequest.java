package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "price_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_lot_id", nullable = false)
    private TenderLot tenderLot;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "med_equip_id", nullable = false)
    private MedEquipment medEquipment;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    @Column(length = 50, nullable = false)
    @Builder.Default
    private String status = "CREATED";

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "response_price", precision = 15, scale = 2)
    private BigDecimal responsePrice;

    @Column(name = "response_date")
    private LocalDate responseDate;

    @Column(name = "response_note", columnDefinition = "TEXT")
    private String responseNote;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
