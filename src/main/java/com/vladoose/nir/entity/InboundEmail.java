package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "inbound_email")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InboundEmail implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Column(length = 998)
    private String subject;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InboundType type;

    @Column(name = "matched_price_request_id")
    private Long matchedPriceRequestId;

    @Column(name = "attachment_name", length = 255)
    private String attachmentName;

    @Column(name = "attachment")
    private byte[] attachment;

    @Column(length = 2000)
    private String excerpt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InboundStatus status = InboundStatus.NEW;

    /** Ящик, из которого пришло письмо (адрес нижним регистром); у писем до V24 — null. */
    @Column(length = 320)
    private String mailbox;

    @Column(name = "imap_uid")
    private Long imapUid;

    @Column(name = "message_id", length = 998)
    private String messageId;

    /** Уведомление в Telegram: null — не ставилось (Telegram выключен, своё письмо, письмо до V24). */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_status", length = 10)
    private NotifyStatus notifyStatus;

    /** Готовый текст уведомления: собирается при записи письма, когда известно, что сделал разбор. */
    @Column(name = "notify_text", columnDefinition = "TEXT")
    private String notifyText;

    @Column(name = "notify_silent", nullable = false)
    private boolean notifySilent;

    @Column(name = "notify_queued_at")
    private OffsetDateTime notifyQueuedAt;

    @Column(name = "notify_attempts", nullable = false)
    private int notifyAttempts;

    @Column(name = "notify_error", length = 300)
    private String notifyError;

    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Override public Market getMarket() { return market; }
    @Override public void setMarket(Market market) { this.market = market; }
}
