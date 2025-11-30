package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tender")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Tender {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tndr_id")
    private Long id;

    private java.math.BigDecimal cost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tndr_fndr_id")
    private TenderFounder founder;
}
