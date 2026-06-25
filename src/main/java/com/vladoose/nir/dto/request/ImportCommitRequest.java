package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ImportCommitRequest {
    @NotNull
    private Long clientFacilityId;
    private String note;
    private List<ColumnMapping> mappings;
    @NotEmpty(message = "Нужна хотя бы одна строка")
    private List<PrivateRequestCreate.Line> lines;
}
