package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@FilterDef(name = "marketFilter", parameters = @ParamDef(name = "market", type = String.class))
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Entity
@Table(name = "tender")
/* UPDATE пишет только изменённые колонки: страница частной заявки сохраняет поле по выходу из него, два наложившихся
   PATCH разных полей иначе затирали бы друг друга (PrivateRequestOverlappingSavesTest) */
@DynamicUpdate
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

    /**
     * Время окончания приёма заявок с площадки (B2): местное время площадки, без пояса — показывается как есть,
     * сравнивается с «сейчас» по часам рынка ({@link Market#zone()}). Только у импортных тендеров; у ручных — пусто,
     * их срок — дата {@link #deadline}. Всегда приходится на дату {@code deadline} (см. {@link #setDeadline}).
     */
    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;

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

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TenderPlatform platform;

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

    /** Контактное лицо частной заявки одной строкой, как написал клиент (у тендеров площадок — разнесённые поля выше). */
    @Column(name = "contact_name")
    private String contactName;

    /** Дата создания (V26): новые строки любого источника; у старых частных заявок заполнена миграцией. */
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(name = "source_ext_id", length = 64)
    private String sourceExtId;

    @Column(length = 100)
    private String region;

    @Column(name = "region_kato", length = 20)
    private String regionKato;

    @Column(name = "customer_name", length = 500)
    private String customerName;

    @Column(name = "customer_bin", length = 20)
    private String customerBin;

    @OneToMany(mappedBy = "tender", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TenderLot> lots = new ArrayList<>();

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /**
     * Срок приёма. Время площадки относится только к своей дате: перенос даты в форме тендера снимает его — иначе
     * тендер закрылся бы по времени прежней даты. Следующий импорт пришлёт срок площадки заново.
     */
    public void setDeadline(LocalDate deadline) {
        this.deadline = deadline;
        if (deadlineAt != null && !deadlineAt.toLocalDate().equals(deadline)) deadlineAt = null;
    }
}
