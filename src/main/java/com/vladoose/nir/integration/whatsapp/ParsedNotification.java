package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;

import java.time.OffsetDateTime;

/** Разобранное уведомление шлюза WhatsApp — Green-API или WAHA (спеки whatsapp-chats §2, §6.5; whatsapp-waha §2). */
public sealed interface ParsedNotification {

    /**
     * Сообщение — входящее или отправленное с телефона. phone — «+цифры» только у личного чата с номером (@c.us);
     * chatName — лучшее имя чата (§6.6); editOf != null — правка сообщения editOf, body — новый текст;
     * viaApi — исходящее отправлено через API (бот, другая интеграция на инстансе), а не человеком с телефона.
     */
    record Message(String account, String chatId, ChatKind kind, String phone, String chatName, String senderName,
                   LeadDirection direction, String idMessage, OffsetDateTime sentAt, ChatMessageType type,
                   String body, FileRef file, String editOf, boolean viaApi) implements ParsedNotification {

        /** Текст для превью и первого сообщения обращения: у файла без подписи — «[фото]» и т.п. */
        public String displayText() {
            if (body != null && !body.isBlank()) return body;
            return switch (type) {
                case IMAGE -> "[фото]";
                case VIDEO -> "[видео]";
                case AUDIO -> "[аудио]";
                case DOCUMENT -> "[документ" + (file != null && file.fileName() != null ? ": " + file.fileName() : "") + "]";
                default -> "[сообщение]";
            };
        }

        public boolean isEdit() { return editOf != null && !editOf.isBlank(); }
    }

    record Delete(String account, String chatId, String deletedId) implements ParsedNotification {}

    record State(String state) implements ParsedNotification {}

    record QuotaExceeded() implements ParsedNotification {}

    record Skip(String reason) implements ParsedNotification {}
}
