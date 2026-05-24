package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "price_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_id", nullable = false)
    private Tender tender;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    @Column(length = 50, nullable = false)
    @Builder.Default
    private String status = "CREATED";

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "response_date")
    private LocalDate responseDate;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "priceRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PriceRequestItem> items = new ArrayList<>();
}
