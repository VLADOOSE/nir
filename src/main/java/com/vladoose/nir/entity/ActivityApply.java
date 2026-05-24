package com.vladoose.nir.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "activity_apply")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityApply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_id", nullable = false)
    private Tender tender;

    @NotBlank(message = "Статус обязателен")
    @Column(length = 50)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "apply", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ApplyItem> items = new ArrayList<>();
}
