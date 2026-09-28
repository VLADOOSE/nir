package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class ChatAttachmentResponse {
    private Long id;
    private String fileName;
    private String mimeType;
    private Long sizeBytes;
    /** Байты есть в АИС; иначе notStoredReason — TOO_LARGE / GROUP / DOWNLOAD_FAILED. */
    private boolean stored;
    private String notStoredReason;
    /** Excel — можно «Разобрать в позиции». */
    private boolean excel;
    /** Сохранённая безопасная картинка — показывается миниатюрой. */
    private boolean image;
}
