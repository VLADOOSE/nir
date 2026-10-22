package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.entity.TechSpecStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TenderLotResponse {
    private Long id;
    private TenderShortResponse tender;
    private Integer lotNumber;
    private String equipName;
    private String manufact;
    private EquipmentTypeResponse equipmentType;
    private Integer quantity;
    private BigDecimal maxCost;
    private Integer maxLengthMm;
    private Integer maxWidthMm;
    private Integer maxHeightMm;
    private BigDecimal maxWeightKg;
    private String requiredSpec;
    private ProposedEquipmentResponse proposedEquipment;
    /** «Модель в работе»: «почему эта модель», кто и когда закрепил (V40). */
    private String modelNote;
    private String modelSetBy;
    private OffsetDateTime modelSetAt;
    /** Последний вывод ИИ (спека 2026-10-21-tender-telegram-bot §10 п. 1). */
    private AiVerdict aiVerdict;
    private String aiProductName;
    private String aiNote;
    private String aiBy;
    private OffsetDateTime aiAt;
    private boolean modelByAi;
    /** Лота больше нет на площадке; остался, потому что на него есть запрос КП или позиция заявки (B5). */
    private OffsetDateTime sourceRemovedAt;
    /** Разбор ТЗ импортного лота (пусто — ручной лот): чип «Есть ТЗ» и шаг «Лоты и ТЗ» читают один признак. */
    private TechSpecStatus techSpecStatus;
    /** Отметки «наш товар» лота (спека 2026-10-19-product-watch §12.3); ставит контроллер. */
    private List<LotProductMatchDto> productMatches = new ArrayList<>();
    /** «Требования ТЗ» (спека 2026-10-20-product-spec-fit §8.1) — только у лота с разобранным ТЗ. */
    private List<String> requirements = new ArrayList<>();

    /** Итог лота на площадке (спека 2026-10-22-tender-results-market-prices §11); пусто — итоги не проверялись. */
    private LotResultSummary result;
}
