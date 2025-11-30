package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "activity_apply")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Activity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenderId;
    private Long facilityId;

    @Column(columnDefinition = "text")
    private String itemsJson; // JSON строка с позициями [{medEquipId:..., qty:...}, ...]

    private OffsetDateTime createdAt;
}
