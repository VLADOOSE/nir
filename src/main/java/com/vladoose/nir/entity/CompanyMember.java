package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "company_member")
@IdClass(CompanyMemberId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CompanyMember {
    @Id
    @Column(name = "company_member_id")
    private Long companyMemberId;

    @Id
    @Column(name = "tndr_id")
    private Long tndrId;

    @Id
    @Column(name = "company_id")
    private Long companyId;

    private String result;
}
