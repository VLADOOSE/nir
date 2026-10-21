package com.vladoose.nir.integration.whatsapp;

import java.time.OffsetDateTime;

/**
 * Исходящий файл в зеркале WhatsApp — отправленный с телефона рабочего номера (или через API). Нужен кнопке бота
 * «📱 Отправлю сам»: PDF «Запрос КП № N …», переданный в WhatsApp, сам подтверждает передачу запроса цен.
 */
public interface OutgoingFileListener {

    OutgoingFileListener NONE = (fileName, chatName, phone, sentAt) -> { };

    void outgoingFile(String fileName, String chatName, String phone, OffsetDateTime sentAt);
}
