package com.vladoose.nir.entity;

import java.io.Serializable;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanyMemberId implements Serializable {
    private Long companyMemberId;
    private Long tndrId;
    private Long companyId;
}
