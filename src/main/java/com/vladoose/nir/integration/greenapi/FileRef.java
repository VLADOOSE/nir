package com.vladoose.nir.integration.greenapi;

/** Файл сообщения в уведомлении: ссылка на скачивание (размера Green-API не сообщает), имя и MIME. */
public record FileRef(String downloadUrl, String fileName, String mimeType) {}
