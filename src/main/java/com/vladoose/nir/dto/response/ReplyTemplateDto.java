package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ReplyTemplateRole;

/** Шаблон ответа (спека 2026-10-19-client-mail-reply §6): id пусто — новый. */
public record ReplyTemplateDto(Long id, String title, String body, ReplyTemplateRole role) {}
