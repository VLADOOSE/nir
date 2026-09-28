package com.vladoose.nir.integration.whatsapp;

/** Ключ шлюза отклонён (401/403), не задан или адрес не собирается — планировщик ставит длинную паузу. */
public class GatewayAuthException extends GatewayException {
    public GatewayAuthException(int status, String message) { super(status, message); }
}
