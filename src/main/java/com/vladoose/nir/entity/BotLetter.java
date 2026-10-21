package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Черновик письма заводу из бота (спека 2026-10-21-tender-telegram-bot §7, V41): кому, какие лоты, статус, сообщения бота. */
@Entity
@Table(name = "bot_letter")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "distributor_id", nullable = false)
    private Long distributorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<BotLetterItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private BotLetterStatus status = BotLetterStatus.DRAFT;

    @Column(name = "price_request_id")
    private Long priceRequestId;

    /** Сообщение бота со списком писем (кнопки «👁 N»). */
    @Column(name = "list_message_id")
    private Long listMessageId;

    /** Открытое фото / PDF бланка — удаляется после отправки или «⬅ Вернуться». */
    @Column(name = "preview_message_id")
    private Long previewMessageId;

    /** № письма в списке: «Письмо 2 из 3», кнопка «👁 2». */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "sent_by", length = 100)
    private String sentBy;

    @Column(length = 300)
    private String error;
}
