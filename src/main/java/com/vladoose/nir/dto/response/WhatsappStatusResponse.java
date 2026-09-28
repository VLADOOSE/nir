package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** Строка «WhatsApp …» на экранах «Чаты» и «Обращения» (спека whatsapp-chats §7). */
@Data
public class WhatsappStatusResponse {
    private boolean enabled;
    private boolean configured;
    private String state;
    private String number;
    private OffsetDateTime lastMessageAt;
    private List<String> warnings;
    private String lastError;
}
