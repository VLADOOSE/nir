package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "price_request_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "price_request_id", nullable = false)
    private PriceRequest priceRequest;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_lot_id", nullable = false)
    private TenderLot tenderLot;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "med_equipment_id")
    private MedEquipment medEquipment;

    /** Пусто — у лота нет количества (СК-Фармация, «приказ ЕД»): поставщику «кол-во уточняется» (B4). */
    @Column(name = "requested_quantity")
    private Integer requestedQuantity;

    @Column(name = "response_price", precision = 15, scale = 2)
    private BigDecimal responsePrice;

    @Column(name = "response_note", columnDefinition = "TEXT")
    private String responseNote;
}
