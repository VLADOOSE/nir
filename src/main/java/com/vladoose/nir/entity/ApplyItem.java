package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "apply_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "apply_id", nullable = false)
    @JsonIgnoreProperties("items")
    private ActivityApply apply;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_lot_id")
    private TenderLot tenderLot;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "med_equip_id")
    private MedEquipment medEquipment;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    @Positive(message = "Предложенная цена должна быть положительной")
    @Column(name = "offered_cost", precision = 15, scale = 2)
    private BigDecimal offeredCost;

    @NotNull(message = "Количество обязательно")
    @Positive(message = "Количество должно быть положительным")
    private Integer quantity;
}
