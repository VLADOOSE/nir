package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "equipment_type_synonym")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EquipmentTypeSynonym {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Нормализованный термин (lower, trim) — подстрока имени/ТЗ лота. */
    @Column(name = "term_norm", length = 255, unique = true, nullable = false)
    private String termNorm;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "equipment_type_id", nullable = false)
    private EquipmentType equipmentType;
}
