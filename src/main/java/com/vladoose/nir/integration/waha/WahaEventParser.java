package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.FileRef;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Событие вебхука WAHA → доменная запись (спека whatsapp-waha §2, §8). Чистая функция: сеть не трогает, на
 * недостающих полях не бросает — кроме случая, когда номер сессии неизвестен вовсе (сообщение ждёт попытки).
 * Форма `_data` движка GOWS в документации WAHA не описана; то, что берём оттуда (Info.PushName / SenderAlt /
 * RecipientAlt, ключ содержимого Message, fileLength), взято из исходников WAHA 2026.9.1 и синтетических событий
 * (WahaJson) — с ЖИВЫМИ событиями ещё НЕ сверено: это задача 13 плана (фикстуры живой WAHA, WahaLiveFixturesTest),
 * отложена до проверки с телефоном. До неё размер файла из события — непроверенная защита от скачивания огромных
 * файлов (DEPLOY.md §7: условие включения).
 */
public final class WahaEventParser {

    static final String CALL_PREFIX = "call:";
    /** Содержимое не для человека: реакции, правки и удаления (у них свои события), служебное шифрования. */
    private static final Set<String> SERVICE = Set.of("protocolMessage", "reactionMessage", "encReactionMessage",
            "editedMessage", "senderKeyDistributionMessage", "messageContextInfo", "keepInChatMessage",
            "pinInChatMessage", "pollUpdateMessage");

    private WahaEventParser() {}

    public static ParsedNotification parse(JsonNode env, String knownAccount) {
        String event = env == null ? "" : env.path("event").asText("");
        JsonNode p = env == null ? MissingNode.getInstance() : env.path("payload");
        switch (event) {
            case "message.any":
                return message(env, p, knownAccount);
            case "message.edited":
                return edited(env, p, knownAccount);
            case "message.revoked":
                return revoked(env, p, knownAccount);
            case "session.status":
                return new ParsedNotification.State(p.path("status").asText(""));
            case "call.received":
                return call(env, p, knownAccount, null);
            case "call.accepted":
                return call(env, p, knownAccount, " — принят");
            case "call.rejected":
                return call(env, p, knownAccount, " — отклонён");
            default:
                return new ParsedNotification.Skip(event.isEmpty() ? "без event" : event);
        }
    }

    /** Ключ дедупликации очереди: «fromMe_rawId» у message.any (вебхук и догонка); у прочих событий — null. */
    public static String messageKey(JsonNode env) {
        if (env == null || !"message.any".equals(env.path("event").asText(""))) return null;
        MessageId id = MessageId.parse(env.path("payload").path("id").asText(null));
        return id == null ? null : id.fromMe() + "_" + id.rawId();
    }

    /** Порядок обработки: время из события (сек), иначе время конверта (мс), иначе сейчас. */
    public static OffsetDateTime eventAt(JsonNode env) {
        long sec = env == null ? 0 : env.path("payload").path("timestamp").asLong(0);
        if (sec > 100_000_000_000L) sec /= 1000;      // пришли миллисекунды
        if (sec > 0) return OffsetDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneOffset.UTC);
        long ms = env == null ? 0 : env.path("timestamp").asLong(0);
        if (ms > 0) return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** id сообщения WAHA: {fromMe}_{chatId}_{rawId}[_{participant}] (спека §2). */
    public record MessageId(boolean fromMe, String chatId, String rawId, String participant) {

        /** null — строка не id сообщения WAHA. */
        public static MessageId parse(String id) {
            if (id == null) return null;
            String[] parts = id.strip().split("_", 4);
            if (parts.length < 3 || !(parts[0].equals("true") || parts[0].equals("false"))
                    || parts[1].isEmpty() || parts[2].isEmpty()) {
                return null;
            }
            return new MessageId(parts[0].equals("true"), chat(parts[1]), parts[2], parts.length == 4 ? parts[3] : null);
        }
    }

    private record Body(ChatMessageType type, String text, FileRef file) {}

    private static ParsedNotification message(JsonNode env, JsonNode p, String knownAccount) {
        MessageId id = MessageId.parse(text(p, "id"));
        if (id == null) return new ParsedNotification.Skip("сообщение без id");
        ChatKind kind = ChatKind.of(id.chatId());
        if (kind == null) return new ParsedNotification.Skip("не чат: " + id.chatId());
        JsonNode data = p.path("_data");
        JsonNode content = data.path("Message");
        if (isService(content)) return new ParsedNotification.Skip("служебное сообщение");
        String pushName = id.fromMe() ? null : pushName(data);
        Body b = body(p, content);
        return new ParsedNotification.Message(account(env, knownAccount), id.chatId(), kind,
                phone(kind, id.chatId(), data, id.fromMe()), kind == ChatKind.GROUP ? null : pushName, pushName,
                id.fromMe() ? LeadDirection.OUT : LeadDirection.IN, id.rawId(), eventAt(env), b.type(), b.text(), b.file(),
                null, id.fromMe() && "api".equals(p.path("source").asText("")));
    }

    private static ParsedNotification edited(JsonNode env, JsonNode p, String knownAccount) {
        MessageId id = MessageId.parse(text(p, "id"));
        if (id == null) return new ParsedNotification.Skip("правка без id");
        ChatKind kind = ChatKind.of(id.chatId());
        if (kind == null) return new ParsedNotification.Skip("не чат: " + id.chatId());
        String original = rawOf(p.path("editedMessageId").asText(""));
        if (original.isEmpty()) return new ParsedNotification.Skip("правка без исходного сообщения");
        JsonNode data = p.path("_data");
        String pushName = id.fromMe() ? null : pushName(data);
        return new ParsedNotification.Message(account(env, knownAccount), id.chatId(), kind,
                phone(kind, id.chatId(), data, id.fromMe()), kind == ChatKind.GROUP ? null : pushName, pushName,
                id.fromMe() ? LeadDirection.OUT : LeadDirection.IN, id.rawId(), eventAt(env), ChatMessageType.TEXT,
                text(p, "body"), null, original, false);
    }

    private static ParsedNotification revoked(JsonNode env, JsonNode p, String knownAccount) {
        MessageId id = MessageId.parse(text(p.path("after"), "id"));
        if (id == null) id = MessageId.parse(text(p.path("before"), "id"));
        if (id == null) id = MessageId.parse(text(p, "id"));
        String deleted = rawOf(p.path("revokedMessageId").asText(""));
        if (id == null || deleted.isEmpty()) return new ParsedNotification.Skip("удаление без чата или id");
        if (ChatKind.of(id.chatId()) == null) return new ParsedNotification.Skip("не чат: " + id.chatId());
        return new ParsedNotification.Delete(account(env, knownAccount), id.chatId(), deleted);
    }

    /** Звонок (спека §8): строка «📞 Входящий звонок», исход — правка той же строки. */
    private static ParsedNotification call(JsonNode env, JsonNode p, String knownAccount, String outcome) {
        String callId = text(p, "id");
        if (callId == null) return new ParsedNotification.Skip("звонок без id");
        String chatId = chat(p.path("from").asText(""));
        if (p.path("isGroup").asBoolean(false)) {
            String group = findGroup(p.path("_data"), 0);
            if (group != null) chatId = group;
        }
        ChatKind kind = ChatKind.of(chatId);
        if (kind == null) return new ParsedNotification.Skip("звонок не из чата: " + chatId);
        String text = (p.path("isVideo").asBoolean(false) ? "📹 Входящий видеозвонок" : "📞 Входящий звонок")
                + (outcome == null ? "" : outcome);
        String externalId = CALL_PREFIX + callId;
        return new ParsedNotification.Message(account(env, knownAccount), chatId, kind,
                phone(kind, chatId, MissingNode.getInstance(), false), null, null, LeadDirection.IN, externalId,
                eventAt(env), ChatMessageType.CALL, text, null, outcome == null ? null : externalId, false);
    }

    private static Body body(JsonNode p, JsonNode content) {
        String caption = text(p, "body");
        String locator = text(p, "id");
        JsonNode media = p.path("media");
        String key = contentKey(content);
        if (key != null) {
            JsonNode c = content.path(key);
            switch (key) {
                case "conversation":
                    return new Body(ChatMessageType.TEXT, first(caption, c.isTextual() ? c.asText() : null), null);
                case "extendedTextMessage":
                    return new Body(ChatMessageType.TEXT, first(caption, text(c, "text")), null);
                case "imageMessage":
                    return media(ChatMessageType.IMAGE, caption, c, media, locator);
                case "videoMessage":
                case "ptvMessage":
                    return media(ChatMessageType.VIDEO, caption, c, media, locator);
                case "audioMessage":
                    return media(ChatMessageType.AUDIO, caption, c, media, locator);
                case "documentMessage":
                    return media(ChatMessageType.DOCUMENT, caption, c, media, locator);
                case "documentWithCaptionMessage":
                    return media(ChatMessageType.DOCUMENT, caption, c.path("message").path("documentMessage"), media, locator);
                case "stickerMessage":
                    return new Body(ChatMessageType.STICKER, "[стикер]", null);
                case "locationMessage":
                case "liveLocationMessage":
                    return new Body(ChatMessageType.LOCATION, location(p.path("location"), c), null);
                case "contactMessage":
                    return new Body(ChatMessageType.CONTACT, "[контакт: " + first(text(c, "displayName"), "без имени") + "]", null);
                case "contactsArrayMessage": {
                    int n = c.path("contacts").size();
                    return new Body(ChatMessageType.CONTACT, n > 0 ? "[контакты: " + n + "]" : "[контакты]", null);
                }
                default:
                    return new Body(ChatMessageType.OTHER, "[сообщение типа " + key + " — смотрите в WhatsApp]", null);
            }
        }
        // содержимого GOWS нет — по нормализованным полям WAHA
        if (p.path("hasMedia").asBoolean(false)) {
            String mime = text(media, "mimetype");
            ChatMessageType t = mime == null ? ChatMessageType.DOCUMENT
                    : mime.startsWith("image/") ? ChatMessageType.IMAGE
                    : mime.startsWith("video/") ? ChatMessageType.VIDEO
                    : mime.startsWith("audio/") ? ChatMessageType.AUDIO : ChatMessageType.DOCUMENT;
            return media(t, caption, MissingNode.getInstance(), media, locator);
        }
        if (p.path("location").isObject()) {
            return new Body(ChatMessageType.LOCATION, location(p.path("location"), MissingNode.getInstance()), null);
        }
        JsonNode vcards = p.path("vCards");
        if (vcards.isArray() && vcards.size() > 0) return new Body(ChatMessageType.CONTACT, contacts(vcards), null);
        if (caption != null) return new Body(ChatMessageType.TEXT, caption, null);
        return new Body(ChatMessageType.OTHER, "[сообщение — смотрите в WhatsApp]", null);
    }

    private static Body media(ChatMessageType type, String caption, JsonNode c, JsonNode media, String locator) {
        String mime = first(text(media, "mimetype"), text(c, "mimetype"));
        String name = first(text(media, "filename"), text(c, "fileName"));
        return new Body(type, first(caption, text(c, "caption")), new FileRef(locator, name, mime, size(c)));
    }

    /** Размер, если WhatsApp его сообщил: proto-поле fileLength (число или строка — зависит от сериализатора). */
    static Long size(JsonNode c) {
        JsonNode n = c.path("fileLength");
        if (n.isMissingNode() || n.isNull()) n = c.path("FileLength");
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isTextual() && n.asText().matches("\\d{1,18}")) return Long.parseLong(n.asText());
        return null;
    }

    private static String location(JsonNode l, JsonNode c) {
        List<String> parts = new ArrayList<>();
        String name = first(text(l, "name"), text(c, "name"));
        String address = first(text(l, "address"), text(c, "address"), text(l, "description"));
        if (name != null) parts.add(name);
        if (address != null && !address.equals(name)) parts.add(address);
        if (parts.isEmpty()) {
            double lat = l.path("latitude").isNumber() ? l.path("latitude").asDouble() : c.path("degreesLatitude").asDouble();
            double lon = l.path("longitude").isNumber() ? l.path("longitude").asDouble() : c.path("degreesLongitude").asDouble();
            parts.add(lat + ", " + lon);
        }
        return "📍 " + String.join(", ", parts);
    }

    private static String contacts(JsonNode vcards) {
        if (vcards.size() > 1) return "[контакты: " + vcards.size() + "]";
        String fn = null;
        for (String line : vcards.path(0).asText("").split("\\R")) {
            if (line.startsWith("FN:")) {
                fn = line.substring(3).strip();
                break;
            }
        }
        return "[контакт: " + first(fn, "без имени") + "]";
    }

    /** Первый не служебный ключ содержимого GOWS; null — содержимого нет. */
    private static String contentKey(JsonNode content) {
        if (!content.isObject()) return null;
        for (Iterator<String> it = content.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!SERVICE.contains(k)) return k;
        }
        return null;
    }

    private static boolean isService(JsonNode content) {
        if (!content.isObject() || content.isEmpty()) return false;
        for (Iterator<String> it = content.fieldNames(); it.hasNext(); ) {
            if (!SERVICE.contains(it.next())) return false;
        }
        return true;
    }

    private static String pushName(JsonNode data) {
        return first(text(data.path("Info"), "PushName"), text(data, "notifyName"));
    }

    /** Телефон: у @c.us — из id; у скрытого @lid — из «альтернативного» jid GOWS, если он есть. */
    static String phone(ChatKind kind, String chatId, JsonNode data, boolean fromMe) {
        if (kind == ChatKind.PERSONAL) return "+" + userOf(chatId);
        if (kind != ChatKind.PERSONAL_HIDDEN) return null;
        String alt = text(data.path("Info"), fromMe ? "RecipientAlt" : "SenderAlt");
        if (alt == null || !(alt.endsWith("@s.whatsapp.net") || alt.endsWith("@c.us"))) return null;
        String digits = userOf(alt);
        return digits.matches("\\d{6,15}") ? "+" + digits : null;
    }

    private static String account(JsonNode env, String knownAccount) {
        String me = userOf(env.path("me").path("id").asText(""));
        if (!me.isEmpty()) return me;
        String known = userOf(knownAccount);
        if (!known.isEmpty()) return known;
        throw new GatewayException(0, "WAHA: номер подключённого WhatsApp ещё не известен — сообщение будет разобрано при следующей попытке");
    }

    /** «7701…@s.whatsapp.net» (внутренний вид GOWS) → «7701…@c.us» (вид API WAHA и Green-API): иначе чат раздвоился бы. */
    static String chat(String jid) {
        String j = jid == null ? "" : jid.strip();
        return j.endsWith("@s.whatsapp.net") ? userOf(j) + "@c.us" : j;
    }

    /** «7701…:12@s.whatsapp.net» → «7701…»: часть до «@», без номера устройства и агента. */
    static String userOf(String jid) {
        String j = jid == null ? "" : jid.strip();
        int at = j.indexOf('@');
        String user = at < 0 ? j : j.substring(0, at);
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '.') return user.substring(0, i);
        }
        return user;
    }

    /** editedMessageId / revokedMessageId бывают и сырыми, и полными — берём сырой id WhatsApp. */
    static String rawOf(String id) {
        MessageId m = MessageId.parse(id);
        return m != null ? m.rawId() : (id == null ? "" : id.strip());
    }

    /** Групповой звонок: jid группы где-то в _data (форма GOWS не описана) — ищем значение «…@g.us». */
    private static String findGroup(JsonNode n, int depth) {
        if (n == null || depth > 4) return null;
        if (n.isTextual()) {
            String v = chat(n.asText());
            return v.endsWith("@g.us") ? v : null;
        }
        for (JsonNode child : n) {
            String g = findGroup(child, depth + 1);
            if (g != null) return g;
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
