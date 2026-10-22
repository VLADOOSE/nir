package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Справочник площадки по БИН (спека §8): название, телефон, почта; «не найден» — площадка БИН не знает. */
@Entity
@Table(name = "goszakup_subject")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoszakupSubject {

    @Id
    @Column(length = 20)
    private String bin;

    @Column(name = "name_ru", length = 500)
    private String nameRu;

    @Column(length = 100)
    private String phone;

    @Column(length = 300)
    private String email;

    @Column(name = "not_found", nullable = false)
    private boolean notFound;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
