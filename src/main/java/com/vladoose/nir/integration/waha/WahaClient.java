package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** WAHA (спека whatsapp-waha §2). Интерфейс — ради фейка в тестах (как GreenApiClient, WestmedClient). */
public interface WahaClient {

    boolean isConfigured();

    /** null — такой сессии нет. */
    WahaSession session(String name);

    /** Создать и сразу запустить: config.metadata.market, ignore историй/каналов/рассылок (спека §7). */
    void createSession(String name, String market);

    void startSession(String name);

    void restartSession(String name);

    /** Отвязать номер: авторизация удаляется, сессия стартует заново с новым QR. */
    void logoutSession(String name);

    /** QR-код привязки (PNG). Сессия не ждёт привязки — GatewayException. */
    byte[] qrPng(String session);

    /** Одно сообщение; downloadMedia=true — WAHA скачивает файл к себе и кладёт ссылку в media.url. */
    JsonNode message(String session, String chatId, String messageId, boolean downloadMedia);

    /** Байты по media.url — только с адреса самой WAHA; больше maxBytes → FileTooLargeException. */
    byte[] downloadFile(String url, long maxBytes);

    /** Сообщения всех чатов не раньше fromEpochSec, по времени, страницей (спека §5.3). */
    List<JsonNode> history(String session, long fromEpochSec, int limit, int offset);

    /** Контакт; null — WAHA его не знает. */
    WahaContact contact(String session, String contactId);

    /** Тема группы; null — неизвестна. */
    String groupSubject(String session, String groupId);

    /** Телефон «+7…» за скрытым номером …@lid; null — WAHA его не знает. */
    String lidPhone(String session, String lid);
}
