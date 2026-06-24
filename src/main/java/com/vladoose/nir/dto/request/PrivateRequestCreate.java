package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class PrivateRequestCreate {
    @NotNull(message = "Клиент обязателен")
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
