package com.vladoose.nir.dto.request;

import com.vladoose.nir.dto.response.ReplyTemplateDto;

import java.util.List;

/** Весь список шаблонов рынка по порядку и подпись (пусто — без подписи). */
public record ReplyTemplatesRequest(List<ReplyTemplateDto> templates, String signature) {}
