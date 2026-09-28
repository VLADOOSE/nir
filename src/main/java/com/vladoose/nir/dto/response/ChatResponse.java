package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ChatResponse {
    private Long id;
    private String title;
    private String phone;
    private boolean group;
    private boolean notClient;
    private OffsetDateTime lastMessageAt;
    private ChatLeadRef lead;
    /** Есть «открытое обращение» по правилам спеки §5.1 — кнопка «Создать обращение» не нужна. */
    private boolean leadOpen;
}
