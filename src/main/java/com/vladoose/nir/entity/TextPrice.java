package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Цена в тексте КП-буклета без таблицы (`supplier_reply_file.text_prices`, JSONB, V42): сумма, код валюты (null — не названа
 * рядом) и фраза документа, где она стоит. Подсказка на экране сверки — не подстановка.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TextPrice(BigDecimal amount, String currency, String text) {}
