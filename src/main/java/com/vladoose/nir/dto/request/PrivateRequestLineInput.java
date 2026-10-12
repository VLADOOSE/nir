package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Новая позиция (сайт, свой текст, список, Excel). Наименование длиннее 255 сервис обрезает (колонка equip_name). */
@Data
public class PrivateRequestLineInput {
    @Size(max = 2000, message = "Наименование слишком длинное")
    private String name;
    @Size(max = 255, message = "Бренд — не больше 255 символов")
    private String brand;
    @Min(value = 1, message = "Количество — от 1")
    @Max(value = 1_000_000, message = "Количество — до 1 000 000")
    private Integer quantity;
    @Size(max = 30, message = "Ед. изм. — не больше 30 символов")
    private String unit;
    @Size(max = 10000, message = "Характеристики — не больше 10 000 символов")
    private String spec;
    @Size(max = 500, message = "Ссылка — не больше 500 символов")
    private String productUrl;
}
