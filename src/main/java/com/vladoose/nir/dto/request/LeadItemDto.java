package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Позиция обращения — и во входе (правка, ручной ввод), и в карточке. */
@Data
public class LeadItemDto {
    private Long id;
    @Size(max = 500) private String name;
    @Size(max = 255) private String brand;
    private Integer quantity;
    @Size(max = 500) private String productUrl;
}
