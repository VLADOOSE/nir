package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@FilterDef(name = "marketFilter", parameters = @ParamDef(name = "market", type = String.class))
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Entity
@Table(name = "tender")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tender implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_number", length = 50, nullable = false)
    private String tenderNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Column(length = 50, nullable = false)
    private String status;

    @Column(name = "purchase_type", length = 50)
    private String purchaseType;

    @Column
    private LocalDate deadline;

    @Column(name = "publish_date")
    private LocalDate publishDate;

    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    @Column(length = 10)
    @Builder.Default
    private String currency = "RUB";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Source source = Source.PUBLIC_TENDER;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(name = "contact_last_name", length = 100)
    private String contactLastName;

    @Column(name = "contact_first_name", length = 100)
    private String contactFirstName;

    @Column(name = "contact_middle_name", length = 100)
    private String contactMiddleName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @OneToMany(mappedBy = "tender", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TenderLot> lots = new ArrayList<>();
}
