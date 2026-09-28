package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** Уведомления Green-API в реальной форме (документация, спека §2) — для тестов парсера, приёма и API. */
public final class GreenApiJson {

    public static final String ACCOUNT = "77000000001";
    public static final String WID = ACCOUNT + "@c.us";
    private static final ObjectMapper M = new ObjectMapper();

    private GreenApiJson() {}

    public static ObjectNode incoming(String chatId, String name, String idMessage, long epochSec, ObjectNode messageData) {
        return envelope("incomingMessageReceived", idMessage, epochSec, chatId, chatId, name, name, name, messageData);
    }

    /** Отправлено с телефона: chatId — получатель, sender — наш номер, senderName — наше собственное имя. */
    public static ObjectNode outgoing(String chatId, String recipientName, String idMessage, long epochSec, ObjectNode messageData) {
        return envelope("outgoingMessageReceived", idMessage, epochSec, chatId, WID, recipientName, "West-Med", "", messageData);
    }

    /** Отправлено ЧЕРЕЗ API (бот, другая интеграция на том же инстансе) — не человек с телефона. */
    public static ObjectNode outgoingApi(String chatId, String recipientName, String idMessage, long epochSec, ObjectNode messageData) {
        return envelope("outgoingAPIMessageReceived", idMessage, epochSec, chatId, WID, recipientName, "West-Med", "", messageData);
    }

    public static ObjectNode group(String groupId, String groupName, String authorChatId, String authorName,
                                   String idMessage, long epochSec, ObjectNode messageData) {
        return envelope("incomingMessageReceived", idMessage, epochSec, groupId, authorChatId, groupName,
                authorName, authorName, messageData);
    }

    public static ObjectNode text(String text) {
        ObjectNode md = type("textMessage");
        md.set("textMessageData", M.createObjectNode().put("textMessage", text));
        return md;
    }

    public static ObjectNode extended(String typeMessage, String text) {
        ObjectNode md = type(typeMessage);
        md.set("extendedTextMessageData", M.createObjectNode().put("text", text).put("description", "").put("title", ""));
        return md;
    }

    public static ObjectNode file(String typeMessage, String url, String fileName, String mime, String caption) {
        ObjectNode md = type(typeMessage);
        md.set("fileMessageData", M.createObjectNode().put("downloadUrl", url).put("caption", caption)
                .put("fileName", fileName).put("jpegThumbnail", "").put("mimeType", mime));
        return md;
    }

    public static ObjectNode edited(String stanzaId, String newText) {
        ObjectNode md = type("editedMessage");
        md.set("editedMessageData", M.createObjectNode().put("textMessage", newText).put("stanzaId", stanzaId));
        return md;
    }

    public static ObjectNode deleted(String stanzaId) {
        ObjectNode md = type("deletedMessage");
        md.set("deletedMessageData", M.createObjectNode().put("stanzaId", stanzaId));
        return md;
    }

    public static ObjectNode reaction(String stanzaId) {
        ObjectNode md = type("reactionMessage");
        md.set("extendedTextMessageData", M.createObjectNode().put("text", "👍"));
        md.set("quotedMessage", M.createObjectNode().put("stanzaId", stanzaId));
        return md;
    }

    public static ObjectNode location(String name, String address, double lat, double lon) {
        ObjectNode md = type("locationMessage");
        md.set("locationMessageData", M.createObjectNode().put("nameLocation", name).put("address", address)
                .put("latitude", lat).put("longitude", lon).put("jpegThumbnail", ""));
        return md;
    }

    public static ObjectNode contact(String displayName) {
        ObjectNode md = type("contactMessage");
        md.set("contactMessageData", M.createObjectNode().put("displayName", displayName).put("vcard", "BEGIN:VCARD\nEND:VCARD"));
        return md;
    }

    public static ObjectNode contacts(String... displayNames) {
        ObjectNode md = type("contactsArrayMessage");
        com.fasterxml.jackson.databind.node.ArrayNode list = M.createArrayNode();
        for (String n : displayNames) list.add(M.createObjectNode().put("displayName", n).put("vcard", "BEGIN:VCARD\nEND:VCARD"));
        md.set("contactsArrayMessageData", M.createObjectNode().set("contacts", list));
        return md;
    }

    public static ObjectNode typeOnly(String typeMessage) { return type(typeMessage); }

    public static ObjectNode state(String state) {
        ObjectNode b = webhook("stateInstanceChanged");
        b.put("stateInstance", state);
        return b;
    }

    public static ObjectNode quota() { return webhook("quotaExceeded"); }

    public static ObjectNode webhook(String typeWebhook) {
        ObjectNode b = M.createObjectNode();
        b.put("typeWebhook", typeWebhook);
        b.set("instanceData", instance());
        b.put("timestamp", Instant.now().getEpochSecond());
        return b;
    }

    private static ObjectNode envelope(String type, String idMessage, long epochSec, String chatId, String sender,
                                       String chatName, String senderName, String senderContactName, ObjectNode messageData) {
        ObjectNode b = M.createObjectNode();
        b.put("typeWebhook", type);
        b.set("instanceData", instance());
        b.put("timestamp", epochSec);
        b.put("idMessage", idMessage);
        ObjectNode sd = M.createObjectNode();
        sd.put("chatId", chatId);
        sd.put("sender", sender);
        sd.put("chatName", chatName);
        sd.put("senderName", senderName);
        sd.put("senderContactName", senderContactName);
        b.set("senderData", sd);
        b.set("messageData", messageData);
        return b;
    }

    private static ObjectNode type(String typeMessage) {
        ObjectNode md = M.createObjectNode();
        md.put("typeMessage", typeMessage);
        return md;
    }

    private static ObjectNode instance() {
        return M.createObjectNode().put("idInstance", 1101).put("wid", WID).put("typeInstance", "whatsapp");
    }
}
