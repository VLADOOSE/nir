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

    /** 💡 — цена поставлена разбором (текст письма или файл ответа) и ещё не сверена; ручное сохранение снимает (V30). */
    @Column(name = "response_auto", nullable = false)
    private boolean responseAuto;

    /** Файл ответа, из которого цена; пусто — не из файла. */
    @Column(name = "response_file_id")
    private Long responseFileId;

    /** Строка файла (с 1), из которой цена. */
    @Column(name = "response_file_row")
    private Integer responseFileRow;

    /** Исходная валюта, цена и курс ответа в чужой валюте (V44): цена в рублях × курс → responsePrice; в валюте рынка — пусто. */
    @Column(name = "response_currency", length = 5)
    private String responseCurrency;

    @Column(name = "response_original", precision = 15, scale = 2)
    private BigDecimal responseOriginal;

    @Column(name = "response_rate", precision = 14, scale = 6)
    private BigDecimal responseRate;
}
