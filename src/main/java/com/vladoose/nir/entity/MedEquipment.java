package com.vladoose.nir.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "med_equipment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedEquipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Наименование обязательно")
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    @NotBlank(message = "Производитель обязателен")
    @Size(max = 255)
    @Column(nullable = false)
    private String manufact;

    @Column(name = "equip_type", length = 100)
    private String equipType;

    @NotNull(message = "Цена обязательна")
    @Positive(message = "Цена должна быть положительной")
    @Column(nullable = false)
    private Integer cost;

    @Positive(message = "Длина должна быть положительной")
    @Column(name = "length_mm")
    private Integer lengthMm;

    @Positive(message = "Ширина должна быть положительной")
    @Column(name = "width_mm")
    private Integer widthMm;

    @Positive(message = "Высота должна быть положительной")
    @Column(name = "height_mm")
    private Integer heightMm;

    @Positive(message = "Вес должен быть положительным")
    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(columnDefinition = "TEXT")
    private String spec;
}
