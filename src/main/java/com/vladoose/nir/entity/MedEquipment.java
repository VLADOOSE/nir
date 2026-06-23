package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String manufact;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "equip_type_id")
    private EquipmentType equipmentType;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(columnDefinition = "TEXT")
    private String spec;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_status", nullable = false, length = 30)
    @Builder.Default
    private RegistrationStatus registrationStatus = RegistrationStatus.UNCHECKED;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "med_registry_reg_number", referencedColumnName = "reg_number")
    private MedRegistry registration;

    @Column(name = "registration_checked_at")
    private OffsetDateTime registrationCheckedAt;
}
