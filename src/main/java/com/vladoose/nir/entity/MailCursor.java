package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Курсор приёма почты: последнее обработанное письмо папки ящика. Не рыночная таблица — ключ ящик + папка (V29). */
@Entity
@Table(name = "mail_cursor")
@IdClass(MailCursorKey.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MailCursor {

    /** Папка «Входящие». */
    public static final String INBOX = "INBOX";
    /** Папка «Отправленные» — её читает ящик info@ (наши ответы клиентам). */
    public static final String SENT = "SENT";

    /** Адрес ящика нижним регистром. */
    @Id
    @Column(length = 320)
    private String mailbox;

    /** Роль папки — INBOX или SENT, а не её имя на сервере: имя у почты своё и бывает локализованным. */
    @Id
    @Column(length = 20)
    @Builder.Default
    private String folder = INBOX;

    /** UIDVALIDITY папки: сменился — UID прежних писем больше ничего не значат. */
    @Column(name = "uid_validity", nullable = false)
    private long uidValidity;

    /** UID последнего обработанного письма; 0 — папка была пуста. */
    @Column(name = "last_uid", nullable = false)
    private long lastUid;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
