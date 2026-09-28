package com.vladoose.nir.integration.waha;

/** Сессия WAHA: status — STOPPED / STARTING / SCAN_QR_CODE / WORKING / FAILED / PASSKEY_*; meId — «7700…@c.us» после привязки. */
public record WahaSession(String name, String status, String meId, String mePushName) {}
