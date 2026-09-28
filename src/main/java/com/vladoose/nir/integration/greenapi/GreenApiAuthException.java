package com.vladoose.nir.integration.greenapi;

/** Ключ отклонён (401/403) или не задан — планировщик ставит длинную паузу. */
public class GreenApiAuthException extends GreenApiException {
    public GreenApiAuthException(int status, String message) { super(status, message); }
}
