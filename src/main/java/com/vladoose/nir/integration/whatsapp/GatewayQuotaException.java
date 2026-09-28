package com.vladoose.nir.integration.whatsapp;

/** Исчерпан лимит тарифа шлюза (Green-API: HTTP 466). */
public class GatewayQuotaException extends GatewayException {
    public GatewayQuotaException(int status, String message) { super(status, message); }
}
