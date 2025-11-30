package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "distributor")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Distributor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;
    private Integer inn;
    private String contact;
}
