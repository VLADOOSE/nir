package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Уведомление Green-API → доменная запись (спека whatsapp-chats §2, §6.5–6.6). Чистая функция:
 * на недостающих полях не бросает (path/asText), неизвестное — Skip или сообщение OTHER.
 */
public final class GreenApiNotificationParser {

    private GreenApiNotificationParser() {}

    public static ParsedNotification parse(JsonNode body) {
        String type = body == null ? "" : body.path("typeWebhook").asText("");
        switch (type) {
            case "incomingMessageReceived":
                return message(body, LeadDirection.IN, false);
            case "outgoingMessageReceived":
                return message(body, LeadDirection.OUT, false);
            case "outgoingAPIMessageReceived":
                return message(body, LeadDirection.OUT, true);
            case "stateInstanceChanged":
                return new ParsedNotification.State(body.path("stateInstance").asText(""));
            case "quotaExceeded":
                return new ParsedNotification.QuotaExceeded();
            default:
                return new ParsedNotification.Skip(type.isEmpty() ? "без typeWebhook" : type);
        }
    }

    /** Вид чата; null — это не чат (истории status@broadcast, каналы …@newsletter, рассылки …@broadcast). */
    static ChatKind kindOf(String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        if (chatId.endsWith("@g.us")) return ChatKind.GROUP;
        if (chatId.endsWith("@c.us")) return ChatKind.PERSONAL;
        if (chatId.endsWith("@lid")) return ChatKind.PERSONAL_HIDDEN;
        return null;
    }

    private static ParsedNotification message(JsonNode b, LeadDirection dir, boolean viaApi) {
        JsonNode sd = b.path("senderData");
        String chatId = sd.path("chatId").asText("");
        ChatKind kind = kindOf(chatId);
        if (kind == null) return new ParsedNotification.Skip("не чат: " + chatId);

        String account = beforeAt(b.path("instanceData").path("wid").asText(""));
        String idMessage = b.path("idMessage").asText("");
        OffsetDateTime at = OffsetDateTime.ofInstant(Instant.ofEpochSecond(b.path("timestamp").asLong(0)), ZoneOffset.UTC);
        String phone = kind == ChatKind.PERSONAL ? "+" + beforeAt(chatId) : null;
        // у исходящих senderName — НАШЕ имя, поэтому имя чата — только chatName (имя получателя)
        String chatName = kind == ChatKind.GROUP ? text(sd, "chatName")
                : dir == LeadDirection.IN ? first(text(sd, "senderContactName"), text(sd, "senderName"), text(sd, "chatName"))
                : text(sd, "chatName");
        String senderName = dir == LeadDirection.IN ? first(text(sd, "senderContactName"), text(sd, "senderName")) : null;

        JsonNode md = b.path("messageData");
        String tm = md.path("typeMessage").asText("");
        if (tm.equals("reactionMessage")) return new ParsedNotification.Skip("реакция");
        if (tm.equals("deletedMessage")) {
            return new ParsedNotification.Delete(account, chatId, md.path("deletedMessageData").path("stanzaId").asText(""));
        }
        if (tm.equals("editedMessage")) {
            JsonNode ed = md.path("editedMessageData");
            return new ParsedNotification.Message(account, chatId, kind, phone, chatName, senderName, dir, idMessage, at,
                    ChatMessageType.TEXT, text(ed, "textMessage"), null, ed.path("stanzaId").asText(""), viaApi);
        }

        ChatMessageType type;
        String bodyText;
        FileRef file = null;
        switch (tm) {
            case "textMessage":
                type = ChatMessageType.TEXT;
                bodyText = text(md.path("textMessageData"), "textMessage");
                break;
            case "extendedTextMessage":
            case "quotedMessage":
                type = ChatMessageType.TEXT;
                bodyText = text(md.path("extendedTextMessageData"), "text");
                break;
            case "imageMessage":
            case "videoMessage":
            case "audioMessage":
            case "documentMessage": {
                type = switch (tm) {
                    case "imageMessage" -> ChatMessageType.IMAGE;
                    case "videoMessage" -> ChatMessageType.VIDEO;
                    case "audioMessage" -> ChatMessageType.AUDIO;
                    default -> ChatMessageType.DOCUMENT;
                };
                JsonNode fd = md.path("fileMessageData");
                bodyText = text(fd, "caption");
                file = new FileRef(text(fd, "downloadUrl"), text(fd, "fileName"), text(fd, "mimeType"));
                break;
            }
            case "stickerMessage":
                type = ChatMessageType.STICKER;
                bodyText = "[стикер]";
                break;
            case "locationMessage":
                type = ChatMessageType.LOCATION;
                bodyText = location(md.path("locationMessageData"));
                break;
            case "contactMessage":
                type = ChatMessageType.CONTACT;
                bodyText = "[контакт: " + first(text(md.path("contactMessageData"), "displayName"), "без имени") + "]";
                break;
            case "contactsArrayMessage":
                type = ChatMessageType.CONTACT;
                bodyText = "[контакты: " + md.path("contactsArrayMessageData").path("contacts").size() + "]";
                break;
            default:
                type = ChatMessageType.OTHER;
                bodyText = "[сообщение типа " + (tm.isEmpty() ? "неизвестно" : tm) + " — смотрите в WhatsApp]";
        }
        return new ParsedNotification.Message(account, chatId, kind, phone, chatName, senderName, dir, idMessage, at,
                type, bodyText, file, null, viaApi);
    }

    private static String location(JsonNode ld) {
        List<String> parts = new ArrayList<>();
        String name = text(ld, "nameLocation");
        String address = text(ld, "address");
        if (name != null) parts.add(name);
        if (address != null) parts.add(address);
        if (parts.isEmpty()) parts.add(ld.path("latitude").asDouble() + ", " + ld.path("longitude").asDouble());
        return "📍 " + String.join(", ", parts);
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText("");
        return v.isBlank() ? null : v;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String beforeAt(String id) {
        int at = id.indexOf('@');
        return at < 0 ? id : id.substring(0, at);
    }
}
