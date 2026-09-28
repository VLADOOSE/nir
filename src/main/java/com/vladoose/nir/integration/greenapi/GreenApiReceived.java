package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.JsonNode;

/** Уведомление из очереди: receiptId — чем удалять, body — само уведомление. */
public record GreenApiReceived(long receiptId, JsonNode body) {}
