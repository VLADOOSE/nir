package com.vladoose.nir.integration.greenapi;

/** Green-API (спека whatsapp-chats §2). Интерфейс — ради фейка в тестах (как WestmedClient). */
public interface GreenApiClient {

    boolean isConfigured();

    /** Голова очереди уведомлений (Green-API отдаёт её снова, пока не удалят); null — за receiveTimeoutSec ничего не пришло. */
    GreenApiReceived receive(int receiveTimeoutSec);

    void delete(long receiptId);

    /** stateInstance: authorized / notAuthorized / blocked / sleepMode / starting / suspended / … */
    String state();

    GreenApiSettings settings();

    /** Файл по downloadUrl из уведомления; больше maxBytes → FileTooLargeException (байты не копятся сверх предела). */
    byte[] download(String url, long maxBytes);
}
