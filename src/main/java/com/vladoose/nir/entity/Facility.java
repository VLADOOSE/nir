package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "facility")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Facility {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;
    private String address;
    private String contact;
}
