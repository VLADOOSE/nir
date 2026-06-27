package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Редактирование частной заявки: строки (правка существующих + добавление + удаление). */
@Data
public class PrivateRequestUpdate {

    private String note;

    @NotEmpty(message = "Нужна хотя бы одна строка")
    private List<Line> lines;

    @Data
    public static class Line {
        private Long lotId;        // null = новая строка
        private String name;
        private String manufact;   // бренд
        private Integer quantity;
    }
}
