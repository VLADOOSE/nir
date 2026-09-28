package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ChatListItemResponse {
    private Long id;
    private String title;
    private String phone;
    private boolean group;
    private boolean notClient;
    private OffsetDateTime lastMessageAt;
    private String lastMessagePreview;
    /** Открытое обращение чата, иначе последнее; null — обращений не было. */
    private ChatLeadRef lead;
}
