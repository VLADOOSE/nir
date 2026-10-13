package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatMessageResponse {
    private Long id;
    private String direction;
    private String senderName;
    private String type;
    /** Тема письма (вид EMAIL); у сообщений WhatsApp — null. */
    private String subject;
    private String body;
    private OffsetDateTime sentAt;
    private boolean edited;
    private boolean deleted;
    /** Файлы по порядку: у WhatsApp — не больше одного, у письма — все вложения. */
    private List<ChatAttachmentResponse> attachments = new ArrayList<>();

    /** Первый файл — прежнее поле ответа (экраны WhatsApp и их тесты читают его). */
    public ChatAttachmentResponse getAttachment() {
        return attachments.isEmpty() ? null : attachments.get(0);
    }
}
