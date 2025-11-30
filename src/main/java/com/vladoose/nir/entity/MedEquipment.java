package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "med_equipment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MedEquipment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "med_equip_id")
    private Long medEquipId;

    private String name;
    private Integer cost;
    private String spec;
    private String manufact;
}