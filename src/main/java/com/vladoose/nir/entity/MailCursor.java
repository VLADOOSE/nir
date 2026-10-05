package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Курсор приёма почты: последнее обработанное письмо ящика. Не рыночная таблица — ключ ящик. */
@Entity
@Table(name = "mail_cursor")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MailCursor {

    /** Адрес ящика нижним регистром. */
    @Id
    @Column(length = 320)
    private String mailbox;

    /** UIDVALIDITY папки: сменился — UID прежних писем больше ничего не значат. */
    @Column(name = "uid_validity", nullable = false)
    private long uidValidity;

    /** UID последнего обработанного письма; 0 — ящик был пуст. */
    @Column(name = "last_uid", nullable = false)
    private long lastUid;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
