package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "med_registry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reg_number", nullable = false, unique = true, length = 100)
    private String regNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(length = 500)
    private String producer;

    @Column(length = 200)
    private String country;

    @Column(name = "reg_date")
    private LocalDate regDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    private Boolean unlimited;

    @Column(name = "imported_at")
    private OffsetDateTime importedAt;
}
