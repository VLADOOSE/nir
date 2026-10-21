package com.vladoose.nir.entity;

/**
 * Черновик письма из бота (спека 2026-10-21-tender-telegram-bot §7): DRAFT → SENDING → SENT; FAILED — почта не приняла
 * (повтор — «↻ Переслать» того же запроса); ELSEWHERE — тем временем отправлено из АИС; CANCELLED — приём закончился.
 */
public enum BotLetterStatus { DRAFT, SENDING, SENT, FAILED, ELSEWHERE, CANCELLED }
