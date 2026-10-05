package com.vladoose.nir.entity;

/** Уведомление о письме в Telegram: ждёт отправки / ушло / не ушло за сутки. NULL в строке — не ставилось. */
public enum NotifyStatus { PENDING, SENT, FAILED }
