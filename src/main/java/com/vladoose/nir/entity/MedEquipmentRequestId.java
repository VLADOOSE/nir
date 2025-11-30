package com.vladoose.nir.entity;

import java.io.Serializable;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedEquipmentRequestId implements Serializable {
    private Long medEquipmentRequestId;
    private Long tndrId;
    private Long medEquipId;
}