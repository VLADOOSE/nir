package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Событие итогов (спека §7, §12): сигнал «звонить», наша заявка, строка сводки, строка «Истории» тендера. */
@Entity
@Table(name = "tender_result_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderResultEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "tender_lot_id", nullable = false)
    private Long tenderLotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ResultEventKind kind;

    /** Время площадки (UTC+5) — когда это случилось на площадке; пусто — неизвестно. */
    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(nullable = false)
    private boolean urgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResultTopic topic;

    /** Тендер повторной попытки в АИС (для ссылки в «Истории»). */
    @Column(name = "ref_tender_id")
    private Long refTenderId;

    /** Ключ повтора у событий цепочки — номер объявления попытки: текст меняется (название поставщика приходит позже), ключ — нет. */
    @Column(name = "ref_key", length = 60)
    private String refKey;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    @Column(name = "digested_at")
    private OffsetDateTime digestedAt;
}
