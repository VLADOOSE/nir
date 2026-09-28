package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladoose.nir.integration.whatsapp.FileTooLargeException;
import com.vladoose.nir.integration.whatsapp.GatewayException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Управляемый фейк WAHA без сети: сессия, сообщения по id, файлы, история «телефона», справочники. */
public class FakeWahaClient implements WahaClient {

    public boolean configured = true;
    /** null — сессии нет. */
    public WahaSession session;
    public final List<String> calls = new ArrayList<>();
    /** messageId → сообщение (с media.url у файлов). */
    public final Map<String, JsonNode> messages = new HashMap<>();
    /** url → байты. */
    public final Map<String, byte[]> files = new HashMap<>();
    /** Сообщения «телефона» для догонки: отдаются с timestamp ≥ from, по возрастанию, страницами. */
    public final List<JsonNode> history = new ArrayList<>();
    public final Map<String, WahaContact> contacts = new HashMap<>();
    public final Map<String, String> groups = new HashMap<>();
    public final Map<String, String> lids = new HashMap<>();
    public byte[] qr = {(byte) 0x89, 'P', 'N', 'G'};
    /** Любой вызов бросает это (WAHA недоступна, ключ отклонён). */
    public RuntimeException failWith;
    /** Только история бросает это (догонка не удалась, остальное работает). */
    public RuntimeException failHistoryWith;

    public long count(String prefix) { return calls.stream().filter(c -> c.startsWith(prefix)).count(); }

    private void call(String c) {
        calls.add(c);
        if (failWith != null) throw failWith;
    }

    @Override public boolean isConfigured() { return configured; }

    @Override
    public WahaSession session(String name) {
        call("session " + name);
        return session;
    }

    @Override
    public void createSession(String name, String market) {
        call("create " + name + " " + market);
        session = new WahaSession(name, "SCAN_QR_CODE", null, null);
    }

    @Override
    public void startSession(String name) {
        call("start " + name);
        session = new WahaSession(name, "STARTING", session == null ? null : session.meId(),
                session == null ? null : session.mePushName());
    }

    @Override public void restartSession(String name) { call("restart " + name); }

    @Override
    public void logoutSession(String name) {
        call("logout " + name);
        session = new WahaSession(name, "SCAN_QR_CODE", null, null);
    }

    @Override
    public byte[] qrPng(String name) {
        call("qr " + name);
        return qr;
    }

    @Override
    public JsonNode message(String s, String chatId, String messageId, boolean downloadMedia) {
        call("message " + chatId + " " + messageId + " " + downloadMedia);
        JsonNode m = messages.get(messageId);
        if (m == null) throw new GatewayException(404, "WAHA: HTTP 404 при подготовке файла сообщения");
        return m;
    }

    @Override
    public byte[] downloadFile(String url, long maxBytes) {
        call("download " + url);
        byte[] b = files.get(url);
        if (b == null) throw new GatewayException(404, "WAHA: HTTP 404 при скачивании файла");
        if (b.length > maxBytes) throw new FileTooLargeException(maxBytes);
        return b;
    }

    @Override
    public List<JsonNode> history(String s, long fromEpochSec, int limit, int offset) {
        call("history " + fromEpochSec + " " + limit + " " + offset);
        if (failHistoryWith != null) throw failHistoryWith;
        List<JsonNode> matching = history.stream()
                .filter(m -> m.path("timestamp").asLong() >= fromEpochSec)
                .sorted(Comparator.comparingLong((JsonNode m) -> m.path("timestamp").asLong()))
                .toList();
        return matching.subList(Math.min(offset, matching.size()), Math.min(offset + limit, matching.size()));
    }

    @Override
    public WahaContact contact(String s, String contactId) {
        call("contact " + contactId);
        return contacts.get(contactId);
    }

    @Override
    public String groupSubject(String s, String groupId) {
        call("group " + groupId);
        return groups.get(groupId);
    }

    @Override
    public String lidPhone(String s, String lid) {
        call("lid " + lid);
        return lids.get(lid);
    }
}
