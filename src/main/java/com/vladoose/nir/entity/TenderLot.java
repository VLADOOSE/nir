package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "tender_lot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_id", nullable = false)
    @JsonIgnoreProperties("lots")
    private Tender tender;

    @Column(name = "lot_number")
    private Integer lotNumber;

    @NotBlank(message = "Название оборудования обязательно")
    @Column(name = "equip_name")
    private String equipName;

    @Column(name = "equip_type", length = 100)
    private String equipType;

    @NotNull(message = "Количество обязательно")
    @Positive(message = "Количество должно быть положительным")
    private Integer quantity;

    @Positive(message = "Максимальная цена должна быть положительной")
    @Column(name = "max_cost", precision = 15, scale = 2)
    private BigDecimal maxCost;

    @Column(name = "max_length_mm")
    private Integer maxLengthMm;

    @Column(name = "max_width_mm")
    private Integer maxWidthMm;

    @Column(name = "max_height_mm")
    private Integer maxHeightMm;

    @Column(name = "max_weight_kg", precision = 10, scale = 2)
    private BigDecimal maxWeightKg;

    @Column(name = "required_spec", columnDefinition = "TEXT")
    private String requiredSpec;
}
