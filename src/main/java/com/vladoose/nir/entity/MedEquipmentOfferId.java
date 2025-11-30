package com.vladoose.nir.entity;

import java.io.Serializable;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedEquipmentOfferId implements Serializable {
    private Long medEquipmentOfferId;
    private Long medEquipmentRequestId;
    private Long tndrId;
    private Long medEquipId;
    private Long companyId;
    private Long companyMemberId;
}

