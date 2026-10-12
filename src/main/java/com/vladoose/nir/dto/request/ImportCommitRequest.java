package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Заявка из Excel (экран «Входящие»): клиент необязателен, отправитель письма — контакт заявки. */
@Data
public class ImportCommitRequest {
    private Long clientFacilityId;
    @Size(max = 255) private String contactName;
    @Size(max = 255) private String contactEmail;
    private String note;
    private List<ColumnMapping> mappings;
    @NotEmpty(message = "Нужна хотя бы одна строка")
    private List<PrivateRequestCreate.Line> lines;
}
