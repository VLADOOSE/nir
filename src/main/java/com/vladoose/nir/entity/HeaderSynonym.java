package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "header_synonym")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HeaderSynonym {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "header_norm", length = 255, unique = true, nullable = false)
    private String headerNorm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LineField field;
}
