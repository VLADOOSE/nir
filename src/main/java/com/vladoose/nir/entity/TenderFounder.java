package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tender_founder")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenderFounder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tndr_fndr_id")
    private Long id;

    private Integer inn;
    private String orgName;
}

