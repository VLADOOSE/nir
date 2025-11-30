package com.vladoose.nir.entity;

import java.io.Serializable;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenderStepId implements Serializable {
    private Long tenderStepId;
    private Long tndrId;
}
