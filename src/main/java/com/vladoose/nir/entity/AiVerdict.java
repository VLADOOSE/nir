package com.vladoose.nir.entity;

/**
 * Вывод ИИ по лоту (спека 2026-10-21-tender-telegram-bot §6): идти, не наше, не ясно; ANALOG — «аналог» (решение оператора
 * 2026-10-21): наш товар не подходит, но ТЗ подходит под чужую модель с РУ в РК — участвовать можно ею.
 */
public enum AiVerdict { GO, NO, UNSURE, ANALOG }
