package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Table(name = "tender_step")
@IdClass(TenderStepId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenderStep {
    @Id
    @Column(name = "tender_step_id")
    private Long tenderStepId;

    @Id
    @Column(name = "tndr_id")
    private Long tndrId;

    private LocalDate startDate;
    private LocalDate endDate;
    private String stepName;
}
