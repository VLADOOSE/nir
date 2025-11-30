package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "med_equipment_offer")
public class MedEquipmentOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "tender_id")
    private Tender tender;

    @ManyToOne
    @JoinColumn(name = "med_equip_id")
    private MedEquipment medEquipment;

    private Double offeredCost;
}
