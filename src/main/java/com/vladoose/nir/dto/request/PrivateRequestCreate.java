package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class PrivateRequestCreate {
    /** Клиент из «Учреждений»; необязателен (спека 2026-10-12, решение 4). */
    private Long clientFacilityId;
    private String note;

    @NotEmpty(message = "Нужна хотя бы одна строка")
    private List<Line> lines;

    @Data
    public static class Line {
        private String name;      // наименование/модель
        private String manufact;  // бренд
        private Integer quantity;
    }
}
