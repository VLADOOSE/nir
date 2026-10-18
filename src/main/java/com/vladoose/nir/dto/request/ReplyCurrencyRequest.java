package com.vladoose.nir.dto.request;

/** Валюта цен файла ответа, выбранная на экране сверки: KZT / RUB / USD / EUR / CNY (MoneyText.CHOOSABLE). */
public record ReplyCurrencyRequest(String currency) {}
