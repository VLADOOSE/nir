package com.vladoose.nir.integration.whatsapp;

/**
 * Откуда приходят уведомления WhatsApp (спека whatsapp-waha §3): Green-API (очередь сервиса) или WAHA (своя очередь
 * whatsapp_inbox). Общий цикл — WhatsappChatSync + WhatsappChatScheduler — от источника не зависит.
 * Все методы, кроме name/isConfigured, зовёт один поток приёма «whatsapp-chats».
 */
public interface WhatsappSource {

    /** Для строки состояния и страницы «Система → WhatsApp»: waha / greenapi. */
    String name();

    boolean isConfigured();

    /** Что не задано — текст для строки состояния (имена переменных, без значений). */
    String configHint();

    /** Где ждут непринятые сообщения, пока приём стоит, — для текстов строки состояния. */
    String waitingNote();

    /** Периодическое, до прохода по очереди: состояние подключения и номер; у WAHA ещё догонка и уборка. */
    void housekeeping(WhatsappStatusHolder status);

    /** Голова очереди; null — пусто. Та же голова приходит снова, пока её не подтвердят. */
    WhatsappNotification next();

    ParsedNotification parse(WhatsappNotification n);

    /** Принято (droppedReason == null) или пропущено как «ядовитое» (droppedReason — свой текст причины). */
    void ack(WhatsappNotification n, String droppedReason);

    /** Байты файла; больше maxBytes → FileTooLargeException; сбой — GatewayException. */
    byte[] download(FileRef ref, long maxBytes);
}
