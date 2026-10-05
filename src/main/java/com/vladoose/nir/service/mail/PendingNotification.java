package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;

/** Уведомление из очереди: только то, что нужно отправке (без тяжёлых колонок письма). */
public record PendingNotification(Long id, String text, boolean silent, OffsetDateTime queuedAt) {}
