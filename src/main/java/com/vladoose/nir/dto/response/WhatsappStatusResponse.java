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
    /** Сколько сообщений за сутки пропущено как «ядовитые» (их надо посмотреть в телефоне). */
    private int droppedCount;
    /** Рынок зеркала — экран подскажет переключиться, если выбран другой (ловушка «не видно данных»). */
    private String market;
    /** Шлюз: waha / greenapi (или опечатка из WHATSAPP_PROVIDER — тогда configured=false и lastError объясняет). */
    private String provider;
}
