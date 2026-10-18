package com.vladoose.nir.entity;

/**
 * Откуда валюта таблицы файла ответа (ReplyTable.currencyFrom, 2026-10-18): TABLE — шапка, колонка «Валюта» или ячейки
 * цен; TEXT — текст документа вне таблицы (с фрагментом, где нашлась); MIXED — в тексте разные валюты или суммы в
 * тысячах (валюта не ясна); NONE — нигде не названа (считается валютой запроса); MANUAL — выбрал человек на экране сверки.
 */
public enum ReplyCurrencySource { TABLE, TEXT, MIXED, NONE, MANUAL }
