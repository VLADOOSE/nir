package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Excel в существующую заявку: разметка колонок (учит словарь) и строки грида. */
@Data
public class ImportLinesRequest {
    private List<ColumnMapping> mappings;
    @NotEmpty(message = "Нет строк с наименованием")
    private List<PrivateRequestCreate.Line> lines;
    /** пусто — APPEND */
    private LinesMode mode;
}
