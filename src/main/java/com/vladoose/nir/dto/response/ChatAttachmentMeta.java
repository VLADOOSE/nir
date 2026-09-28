package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.AttachmentNotStoredReason;

/** Файл сообщения без байтов — для ленты (байты читаются только при скачивании). */
public record ChatAttachmentMeta(Long id, Long messageId, String fileName, String mimeType, Long sizeBytes,
                                 AttachmentNotStoredReason notStoredReason) {
    public boolean stored() { return notStoredReason == null; }
}
