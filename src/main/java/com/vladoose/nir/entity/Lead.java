package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Обращение клиента (коммерция West-Med): заявка с сайта, звонок, сообщение.
 * Рыночная сущность (§6 CLAUDE.md): @Filter + листенер штампа; @FilterDef объявлен ОДИН раз — на Tender.
 * Позиции и лента — дети с cascade=ALL/orphanRemoval: менять ТОЛЬКО через коллекции (урок §7).
 */
@Entity
@Table(name = "lead")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Lead implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadChannel channel;

    /** Кто создал запись: «westmed.kz», «manual», позже «vital-spb.kz», «whatsapp», «pbx». */
    @Column(nullable = false, length = 40)
    private String source;

    /** Id у источника; пара (source, externalId) уникальна. У ручных обращений — null. */
    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    /** +7XXXXXXXXXX — ключ «к какому обращению относится звонок/сообщение» для будущих каналов. */
    @Column(name = "phone_norm", length = 20)
    private String phoneNorm;

    @Column(name = "contact_email")
    private String contactEmail;

    private String company;

    @Column(columnDefinition = "TEXT")
    private String message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 30)
    private LeadCloseReason closeReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "private_request_id")
    private Tender privateRequest;

    /** Чат WhatsApp обращения (спека whatsapp-chats §4). У одного чата со временем может быть несколько обращений. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id")
    private Chat chat;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Статус у источника, как его знает АИС. */
    @Column(name = "ext_status", length = 20)
    private String extStatus;

    /** Что надо записать в источник; null — нечего. */
    @Column(name = "ext_status_pending", length = 20)
    private String extStatusPending;

    @Column(name = "ext_sync_error", length = 500)
    private String extSyncError;

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @BatchSize(size = 50)   // список до 300 обращений: позиции подгружаются пачками, а не по одной
    @Builder.Default
    private List<LeadItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("occurredAt ASC, id ASC")
    @Builder.Default
    private List<LeadEvent> events = new ArrayList<>();

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (receivedAt == null) receivedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }

    public void addItem(String name, String brand, int quantity, String productUrl) {
        items.add(LeadItem.builder().lead(this).lineNo(items.size() + 1)
                .name(name).brand(brand).quantity(Math.max(quantity, 1)).productUrl(productUrl).build());
    }

    public LeadEvent addEvent(LeadEventType type, String author, String body) {
        LeadEvent e = LeadEvent.builder().lead(this).occurredAt(OffsetDateTime.now())
                .type(type).author(author).body(body).build();
        events.add(e);
        return e;
    }
}
