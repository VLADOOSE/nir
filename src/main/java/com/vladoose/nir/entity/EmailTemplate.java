package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_template")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 2)
    private Market market;

    @Column(name = "subject_template", columnDefinition = "TEXT", nullable = false)
    private String subjectTemplate;

    @Column(name = "body_template", columnDefinition = "TEXT", nullable = false)
    private String bodyTemplate;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
