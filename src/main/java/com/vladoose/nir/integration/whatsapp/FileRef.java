package com.vladoose.nir.integration.whatsapp;

/**
 * Файл сообщения в уведомлении. locator — чем его скачать; понимает только источник, который его выдал (Green-API —
 * ссылка downloadUrl, WAHA — полный id сообщения). sizeBytes — размер, если шлюз его сообщил (Green-API — нет):
 * больше предела → не качаем вовсе (WAHA держит скачиваемый файл целиком в памяти, спека whatsapp-waha §6).
 */
public record FileRef(String locator, String fileName, String mimeType, Long sizeBytes) {

    public FileRef(String locator, String fileName, String mimeType) {
        this(locator, fileName, mimeType, null);
    }
}
