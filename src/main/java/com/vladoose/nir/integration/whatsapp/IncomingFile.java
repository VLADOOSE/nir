package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.entity.AttachmentNotStoredReason;

/** Файл сообщения для записи (спека §6.4). Инвариант: content == null ⇔ notStoredReason != null. */
public record IncomingFile(String fileName, String mimeType, byte[] content, AttachmentNotStoredReason notStoredReason) {

    public static IncomingFile stored(FileRef ref, byte[] content) {
        return new IncomingFile(ref.fileName(), ref.mimeType(), content, null);
    }

    public static IncomingFile notStored(FileRef ref, AttachmentNotStoredReason reason) {
        return new IncomingFile(ref.fileName(), ref.mimeType(), null, reason);
    }
}
