package com.vladoose.nir.integration.greenapi;

/** HTTP 466 — исчерпан лимит тарифа Developer. */
public class GreenApiQuotaException extends GreenApiException {
    public GreenApiQuotaException(int status, String message) { super(status, message); }
}
