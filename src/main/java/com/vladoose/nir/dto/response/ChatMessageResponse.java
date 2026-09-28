package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ChatMessageResponse {
    private Long id;
    private String direction;
    private String senderName;
    private String type;
    private String body;
    private OffsetDateTime sentAt;
    private boolean edited;
    private boolean deleted;
    private ChatAttachmentResponse attachment;
}
