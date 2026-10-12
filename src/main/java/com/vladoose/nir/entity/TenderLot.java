package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tender_lot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_id", nullable = false)
    private Tender tender;

    @Column(name = "lot_number")
    private Integer lotNumber;

    /** Реальный код лота на площадке (СК-Фармация «1040409-Т1») — ключ связи с файлами ТЗ. */
    @Column(name = "source_lot_code", length = 50)
    private String sourceLotCode;

    @Column(name = "equip_name")
    private String equipName;

    @Column(length = 255)
    private String manufact;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "equip_type_id")
    private EquipmentType equipmentType;

    private Integer quantity;

    @Column(name = "max_cost", precision = 15, scale = 2)
    private BigDecimal maxCost;

    @Column(name = "max_length_mm")
    private Integer maxLengthMm;

    @Column(name = "max_width_mm")
    private Integer maxWidthMm;

    @Column(name = "max_height_mm")
    private Integer maxHeightMm;

    @Column(name = "max_weight_kg", precision = 10, scale = 2)
    private BigDecimal maxWeightKg;

    @Column(name = "required_spec", columnDefinition = "TEXT")
    private String requiredSpec;

    /** Исход последней попытки фонового разбора техспеки; NULL — в очередь не ставился. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tech_spec_status", length = 20)
    private TechSpecStatus techSpecStatus;

    @Column(name = "tech_spec_attempted_at")
    private OffsetDateTime techSpecAttemptedAt;

    @Column(name = "source_removed_at")
    private OffsetDateTime sourceRemovedAt;

    /** Предложенная (одобренная) модель каталога; подставляется в запрос КП по лоту. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "proposed_equipment_id")
    private MedEquipment proposedEquipment;

    /** Ед. изм. позиции частной заявки; пусто = «шт». */
    @Column(length = 30)
    private String unit;

    /** Ссылка на товар westmed.kz, если позиция выбрана из каталога сайта. */
    @Column(name = "product_url", length = 500)
    private String productUrl;

    /** РУ позиции частной заявки; пусто — не проверено. Правка наименования или бренда пересчитывает подсказку. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reg_status", length = 20)
    private LineRegStatus regStatus;

    @Column(name = "reg_number", length = 100)
    private String regNumber;

    /** Похожесть подсказки реестра — для калибровки порога; на экране не печатается. */
    @Column(name = "reg_score", precision = 5, scale = 3)
    private BigDecimal regScore;
}
