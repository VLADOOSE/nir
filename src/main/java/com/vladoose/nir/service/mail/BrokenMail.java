package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;

/** Письмо, которое не разобралось или не записалось: что удалось прочитать (null — не прочитано) и класс ошибки. */
public record BrokenMail(long uid, String messageId, String from, String subject, OffsetDateTime receivedAt, String errorClass) {}
