package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Сообщение-пачка «Потенциально наших тендеров» (спека 2026-10-21-tender-telegram-bot §4, V41): какие тендеры в нём. */
@Entity
@Table(name = "product_digest")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /** message_id в Telegram; пусто — ещё не ушло. */
    @Column(name = "message_id")
    private Long messageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tender_ids", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Long> tenderIds = new ArrayList<>();
}
