package com.vladoose.nir.integration.whatsapp;

/** Файл больше chats.whatsapp.max-file-mb — скачивание оборвано, в базу не пишем. */
public class FileTooLargeException extends RuntimeException {
    public FileTooLargeException(long maxBytes) {
        super("файл больше " + (maxBytes / (1024 * 1024)) + " МБ");
    }
}
