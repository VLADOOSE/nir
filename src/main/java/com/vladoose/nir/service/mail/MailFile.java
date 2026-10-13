package com.vladoose.nir.service.mail;

/**
 * Файл письма ящика info@: имя, базовый тип нижним регистром, размер и байты. content == null — файл больше предела:
 * он не сохраняется (в переписке — название с пометкой «откройте в почте»), размер тогда неизвестен — null.
 */
public record MailFile(String name, String mimeType, Long size, byte[] content) {

    public boolean stored() {
        return content != null;
    }

    /** bytes — прочитано не больше max + 1 байта: длиннее max — файл больше предела. */
    static MailFile of(String name, String mimeType, byte[] bytes, int max) {
        return bytes.length > max ? new MailFile(name, mimeType, null, null)
                : new MailFile(name, mimeType, (long) bytes.length, bytes);
    }
}
