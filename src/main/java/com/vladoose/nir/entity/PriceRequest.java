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

    /** Адрес, на который письмо приняла почта; пусто — письма не было или оно не ушло. */
    @Column(name = "sent_to")
    private String sentTo;

    /** Исход последней отправки письма; пусто у запросов до V26. */
    @Enumerated(EnumType.STRING)
    @Column(name = "letter_status", length = 20)
    private LetterStatus letterStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @OneToMany(mappedBy = "priceRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PriceRequestItem> items = new ArrayList<>();
}
