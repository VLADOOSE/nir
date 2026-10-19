package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Шаблон ответа клиенту или подпись письма (V35, спека 2026-10-19-client-mail-reply §6). Не рыночная сущность с явным
 * рынком (как email_template): читается и пишется по MarketContext, без @Filter.
 */
@Entity
@Table(name = "reply_template")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReplyTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReplyTemplateRole role;

    @Column(nullable = false)
    private int position;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
