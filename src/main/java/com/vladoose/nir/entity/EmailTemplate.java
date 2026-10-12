package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_template")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 2)
    private Market market;

    @Column(name = "subject_template", columnDefinition = "TEXT", nullable = false)
    private String subjectTemplate;

    @Column(name = "body_template", columnDefinition = "TEXT", nullable = false)
    private String bodyTemplate;

    /** Запрос на бланке (спека 2026-10-12 §6.4); пусто — зашитый текст RfqTexts. */
    @Column(name = "rfq_title", columnDefinition = "TEXT")
    private String rfqTitle;

    @Column(name = "rfq_intro", columnDefinition = "TEXT")
    private String rfqIntro;

    /** Пункты «В предложении просим указать», по одному на строку. */
    @Column(name = "rfq_ask", columnDefinition = "TEXT")
    private String rfqAsk;

    /** Строка о конечном заказчике — общая для письма ({{заказчик}}) и документа. */
    @Column(name = "rfq_client_line", columnDefinition = "TEXT")
    private String rfqClientLine;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
