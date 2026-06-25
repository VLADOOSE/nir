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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Override public Market getMarket() { return market; }
    @Override public void setMarket(Market market) { this.market = market; }
}
