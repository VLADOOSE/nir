package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;

/**
 * Чат рабочего номера WhatsApp (спека 2026-09-28-whatsapp-chats-green-api §4). Рыночная сущность:
 * @Filter + листенер штампа; @FilterDef объявлен ОДИН раз — на Tender (§6 CLAUDE.md).
 * Сообщения и файлы — отдельные сущности без рыночного фильтра: наружу только через свой чат.
 */
@Entity
@Table(name = "chat")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Chat implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadChannel channel;

    /** Номер подключённого аккаунта (wid без «@c.us»). */
    @Column(nullable = false, length = 40)
    private String account;

    /** chatId Green-API: «7701…@c.us» / «…@g.us» / «…@lid». */
    @Column(name = "external_chat_id", nullable = false, length = 100)
    private String externalChatId;

    @Column(name = "is_group", nullable = false)
    private boolean group;

    private String title;

    @Column(name = "phone_norm", length = 20)
    private String phoneNorm;

    /** «Не клиент» — из чата не создаются обращения (коллега, поставщик, спам). */
    @Column(name = "not_client", nullable = false)
    private boolean notClient;

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    @Column(name = "last_message_preview", length = 300)
    private String lastMessagePreview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
