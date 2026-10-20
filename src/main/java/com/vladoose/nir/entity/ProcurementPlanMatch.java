package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

/** Группа пункта плана (V36): строка на пару «пункт × группа», каскадом от пункта и группы. */
@Entity
@Table(name = "procurement_plan_match")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcurementPlanMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_point_id", nullable = false)
    private Long planPointId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(length = 100)
    private String word;
}
