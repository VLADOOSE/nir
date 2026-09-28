package com.vladoose.nir.integration.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;

/** Уведомление из очереди источника: id — чем подтверждать (receiptId Green-API / строка whatsapp_inbox), body — само уведомление. */
public record WhatsappNotification(long id, JsonNode body) {}
