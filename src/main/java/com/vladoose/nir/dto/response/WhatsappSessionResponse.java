package com.vladoose.nir.dto.response;

import lombok.Data;

/** «Система → WhatsApp» (спека whatsapp-waha §7, §9). */
@Data
public class WhatsappSessionResponse {
    /** waha / greenapi. */
    private String provider;
    private boolean enabled;
    private boolean configured;
    /** WAHA: STOPPED / STARTING / SCAN_QR_CODE / WORKING / FAILED / PASSKEY_*; Green-API: stateInstance. */
    private String status;
    private String number;
    /** Имя профиля привязанного номера. */
    private String name;
    /** Номер ждёт привязки — страница показывает QR. */
    private boolean qrAvailable;
    /** WAHA не ответила на запрос статуса — текст без адреса и ключа. */
    private String error;
}
