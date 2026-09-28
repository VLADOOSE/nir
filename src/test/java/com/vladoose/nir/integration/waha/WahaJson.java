package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * События WAHA в форме документации (спека whatsapp-waha §2): конверт вебхука {id, timestamp(мс), event, session,
 * metadata, me, payload, engine}; payload message.any — нормализованные поля WAHA + сырые `_data` движка GOWS
 * (Info.PushName / SenderAlt / RecipientAlt, Message.<ключ содержимого>). Форма `_data` сверяется с живой WAHA
 * (задача 13 плана): расхождение — править и здесь, и в парсере.
 */
public final class WahaJson {

    public static final String ACCOUNT = "77000000001";
    public static final String ME = ACCOUNT + "@c.us";
    public static final String SESSION = "westmed";
    static final ObjectMapper M = new ObjectMapper();

    private WahaJson() {}

    public static ObjectNode envelope(String event, ObjectNode payload) {
        ObjectNode e = M.createObjectNode();
        e.put("id", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        e.put("timestamp", System.currentTimeMillis());
        e.put("event", event);
        e.put("session", SESSION);
        e.set("metadata", M.createObjectNode().put("market", "KZ"));
        e.set("me", M.createObjectNode().put("id", ME).put("pushName", "West-Med"));
        e.set("payload", payload);
        e.put("engine", "GOWS");
        return e;
    }

    public static String messageId(boolean fromMe, String chatId, String rawId) { return fromMe + "_" + chatId + "_" + rawId; }

    public static ObjectNode incomingText(String chatId, String pushName, String rawId, long epochSec, String text) {
        ObjectNode p = message(false, chatId, rawId, null, epochSec, text, pushName);
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    /** Ответ с телефона: fromMe, source=app. */
    public static ObjectNode phoneReply(String chatId, String rawId, long epochSec, String text) {
        ObjectNode p = message(true, chatId, rawId, null, epochSec, text, null);
        p.put("source", "app");
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    /** Отправлено через API WAHA (бот, другая интеграция): fromMe, source=api. */
    public static ObjectNode apiSent(String chatId, String rawId, long epochSec, String text) {
        ObjectNode p = message(true, chatId, rawId, null, epochSec, text, null);
        p.put("source", "api");
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    public static ObjectNode groupText(String groupId, String participant, String pushName, String rawId, long epochSec, String text) {
        ObjectNode p = message(false, groupId, rawId, participant, epochSec, text, pushName);
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    /** Файл без скачивания (WAHA_EVENTS_DOWNLOAD_MEDIA=false): hasMedia, media без url, размер — в содержимом GOWS. */
    public static ObjectNode incomingFile(String chatId, String pushName, String rawId, long epochSec, String contentKey,
                                          String fileName, String mime, Long fileLength, String caption) {
        ObjectNode p = message(false, chatId, rawId, null, epochSec, caption, pushName);
        return envelope("message.any", file(p, contentKey, fileName, mime, fileLength, caption));
    }

    public static ObjectNode groupFile(String groupId, String participant, String pushName, String rawId, long epochSec,
                                       String contentKey, String fileName, String mime, Long fileLength) {
        ObjectNode p = message(false, groupId, rawId, participant, epochSec, null, pushName);
        return envelope("message.any", file(p, contentKey, fileName, mime, fileLength, null));
    }

    /** Заменить содержимое GOWS (`_data.Message`) — стикер, геоточка, контакт, реакция, служебное. */
    public static ObjectNode withContent(ObjectNode env, String key, JsonNode value) {
        ObjectNode content = M.createObjectNode();
        content.set(key, value);
        ((ObjectNode) env.get("payload").get("_data")).set("Message", content);
        return env;
    }

    /** `_data.Info` события — дописать SenderAlt/RecipientAlt и т.п. */
    public static ObjectNode info(ObjectNode env) { return (ObjectNode) env.get("payload").get("_data").get("Info"); }

    static ObjectNode content(ObjectNode payload) { return (ObjectNode) payload.get("_data").get("Message"); }

    public static ObjectNode edited(String chatId, boolean fromMe, String editRawId, String originalId, long epochSec, String newText) {
        ObjectNode p = message(fromMe, chatId, editRawId, null, epochSec, newText, fromMe ? null : "Айгерим");
        p.put("editedMessageId", originalId);
        return envelope("message.edited", p);
    }

    public static ObjectNode revoked(String chatId, boolean fromMe, String revokeRawId, String revokedId) {
        ObjectNode p = M.createObjectNode();
        p.set("after", message(fromMe, chatId, revokeRawId, null, System.currentTimeMillis() / 1000, null, null));
        p.putNull("before");
        p.put("revokedMessageId", revokedId);
        return envelope("message.revoked", p);
    }

    public static ObjectNode sessionStatus(String status) {
        ObjectNode p = M.createObjectNode();
        p.put("name", SESSION);
        p.put("status", status);
        return envelope("session.status", p);
    }

    /** event — call.received / call.accepted / call.rejected; groupId != null — групповой звонок (jid группы в _data). */
    public static ObjectNode call(String event, String callId, String from, long epochSec, boolean video, String groupId) {
        ObjectNode p = M.createObjectNode();
        p.put("id", callId);
        p.put("from", from);
        p.put("timestamp", epochSec);
        p.put("isVideo", video);
        p.put("isGroup", groupId != null);
        ObjectNode data = M.createObjectNode();
        data.put("CallID", callId);
        data.put("From", toServer(from));
        if (groupId != null) data.put("GroupJID", groupId);
        p.set("_data", data);
        return envelope(event, p);
    }

    private static ObjectNode message(boolean fromMe, String chatId, String rawId, String participant, long epochSec,
                                      String body, String pushName) {
        ObjectNode p = M.createObjectNode();
        p.put("id", messageId(fromMe, chatId, rawId) + (participant == null ? "" : "_" + participant));
        p.put("timestamp", epochSec);
        p.put("from", fromMe ? ME : chatId);
        p.put("fromMe", fromMe);
        p.put("to", fromMe ? chatId : ME);
        if (participant != null) p.put("participant", participant);
        if (body != null) p.put("body", body); else p.putNull("body");
        p.put("hasMedia", false);
        p.putNull("media");
        p.put("ack", fromMe ? 1 : 0);
        ObjectNode info = M.createObjectNode();
        info.put("Chat", toServer(chatId));
        info.put("Sender", toServer(fromMe ? ME : participant != null ? participant : chatId));
        info.put("IsFromMe", fromMe);
        info.put("IsGroup", chatId.endsWith("@g.us"));
        info.put("ID", rawId);
        if (pushName != null) info.put("PushName", pushName);
        ObjectNode data = M.createObjectNode();
        data.set("Info", info);
        data.set("Message", M.createObjectNode());
        p.set("_data", data);
        return p;
    }

    private static ObjectNode file(ObjectNode p, String contentKey, String fileName, String mime, Long fileLength, String caption) {
        p.put("hasMedia", true);
        ObjectNode media = M.createObjectNode();
        media.putNull("url");
        media.put("mimetype", mime);
        if (fileName != null) media.put("filename", fileName); else media.putNull("filename");
        p.set("media", media);
        ObjectNode c = M.createObjectNode();
        c.put("mimetype", mime);
        if (fileName != null) c.put("fileName", fileName);
        if (fileLength != null) c.put("fileLength", fileLength);
        if (caption != null) c.put("caption", caption);
        content(p).set(contentKey, c);
        return p;
    }

    /** Внутренний вид jid у GOWS: «…@s.whatsapp.net» вместо «…@c.us». */
    static String toServer(String jid) {
        return jid != null && jid.endsWith("@c.us") ? jid.substring(0, jid.length() - 5) + "@s.whatsapp.net" : jid;
    }
}
