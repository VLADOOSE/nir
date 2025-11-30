package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "med_equipment_request")
@IdClass(MedEquipmentRequestId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MedEquipmentRequest {
    @Id
    @Column(name = "med_equipment_request_id")
    private Long medEquipmentRequestId;

    @Id
    @Column(name = "tndr_id")
    private Long tndrId;

    @Id
    @Column(name = "med_equip_id")
    private Long medEquipId;

    private String medEquipName;
    private Integer cost;
    private String spec;
}