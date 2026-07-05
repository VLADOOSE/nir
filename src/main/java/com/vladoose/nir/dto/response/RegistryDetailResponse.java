package com.vladoose.nir.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** Описание изделия из карточки НЦЭЛС (кеш в med_registry); null-поля — «в карточке не заполнено». */
@Data
@Builder
public class RegistryDetailResponse {
    private String regNumber;
    private String riskClass;
    private String purpose;
    private String useArea;
    private String techChars;
    private String miKind;
    private String miKindDef;
    private OffsetDateTime fetchedAt;
}
