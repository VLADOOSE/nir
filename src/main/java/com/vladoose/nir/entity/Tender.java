package com.vladoose.nir.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tender")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Номер тендера обязателен")
    @Column(name = "tender_number", length = 50, nullable = false)
    private String tenderNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @NotBlank(message = "Статус обязателен")
    @Column(length = 50, nullable = false)
    private String status;

    @NotBlank(message = "Способ закупки обязателен")
    @Column(name = "purchase_type", length = 50)
    private String purchaseType;

    @NotNull(message = "Дедлайн обязателен")
    @Column(nullable = false)
    private LocalDate deadline;

    @Column(name = "publish_date")
    private LocalDate publishDate;

    @PositiveOrZero(message = "Стоимость не может быть отрицательной")
    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    @Column(length = 10)
    @Builder.Default
    private String currency = "RUB";

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

    @OneToMany(mappedBy = "tender", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TenderLot> lots = new ArrayList<>();
}
