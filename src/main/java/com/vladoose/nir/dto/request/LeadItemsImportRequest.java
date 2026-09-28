package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** Позиции обращения из Excel-файла чата (спека whatsapp-chats §9.4). */
@Data
public class LeadItemsImportRequest {
    /** Разметка колонок оператора — учит словарь заголовков. */
    private List<ColumnMapping> mappings;
    @Valid
    private List<LeadItemDto> items;
    /** REPLACE — заменить позиции, APPEND — добавить в конец. */
    @NotBlank
    private String mode;
}
