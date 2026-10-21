package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Entity
@Table(name = "price_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceRequest implements MarketScoped {

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

    /** «Ответить до» из окна запроса цен; в письме — вместо срока тендера. */
    @Column(name = "reply_by")
    private LocalDate replyBy;

    /**
     * Адрес последней отправки письма (несколько — через запятую); принят ли он — letterStatus. При сбое SMTP адрес
     * остаётся: «↻ Переслать» вписанной почты знает, куда слать.
     */
    @Column(name = "sent_to")
    private String sentTo;

    /** Исход последней отправки письма; пусто у запросов до V26. */
    @Enumerated(EnumType.STRING)
    @Column(name = "letter_status", length = 20)
    private LetterStatus letterStatus;

    /** Клиент заявки указан поставщику (галочка «Указать клиента», спека 2026-10-12 §6.2). */
    @Column(name = "disclose_client", nullable = false)
    private boolean discloseClient;

    /** К письму приложен запрос на бланке (Word). */
    @Column(name = "with_document", nullable = false)
    private boolean withDocument;

    /** В документе — подпись и печать. */
    @Column(name = "with_stamp", nullable = false)
    private boolean withStamp;

    /** Письмо ушло не на почту из карточки, а на вписанную в окне запроса (она — в sentTo). */
    @Column(name = "email_override", nullable = false)
    private boolean emailOverride;

    /** Кто отправил: «vlad», из бота — «vlad, Telegram» (V41); у запросов до V41 — пусто. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @OneToMany(mappedBy = "priceRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PriceRequestItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
