package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tender_lot")
/* только изменённые колонки — наложившиеся правки позиции не затирают друг друга (PrivateRequestOverlappingSavesTest) */
@DynamicUpdate
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

    /** Сколько раз поиск нашей продукции пытался разобрать ТЗ лота (спека 2026-10-19-product-watch §7.4): не больше 3. */
    @Column(name = "tech_spec_attempts", nullable = false)
    @Builder.Default
    private int techSpecAttempts = 0;

    @Column(name = "source_removed_at")
    private OffsetDateTime sourceRemovedAt;

    /** Предложенная (одобренная) модель каталога; подставляется в запрос КП по лоту. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "proposed_equipment_id")
    private MedEquipment proposedEquipment;

    /** «Почему эта модель» — заметка к модели в работе (вывод ИИ), V40. */
    @Column(name = "model_note", length = 1000)
    private String modelNote;

    /** Кто и когда последним ставил или снимал модель в работе (V40). */
    @Column(name = "model_set_by", length = 100)
    private String modelSetBy;

    @Column(name = "model_set_at")
    private OffsetDateTime modelSetAt;

    /** Последний вывод ИИ по лоту (спека 2026-10-21-tender-telegram-bot §6, V41). */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_verdict", length = 10)
    private AiVerdict aiVerdict;

    /** Наш товар из совета ИИ — только из кандидатов этого лота. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_product_id")
    private SiteProduct aiProduct;

    @Column(name = "ai_note", length = 500)
    private String aiNote;

    @Column(name = "ai_by", length = 100)
    private String aiBy;

    @Column(name = "ai_at")
    private OffsetDateTime aiAt;

    /** Модель в работе закрепил ИИ по ответу: следующий ответ ИИ её заменит, ручную — нет. */
    @Column(name = "model_by_ai", nullable = false)
    private boolean modelByAi;

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
