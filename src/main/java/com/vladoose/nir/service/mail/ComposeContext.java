package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;

import java.time.OffsetDateTime;

/** Ящик, рынок (валюта, часовой пояс, ?market= в ссылке), публичный адрес АИС ("" — без ссылки), время постановки в очередь. */
public record ComposeContext(String mailbox, Market market, String publicUrl, OffsetDateTime queuedAt) {}
