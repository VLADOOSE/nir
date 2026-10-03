package com.vladoose.nir.dto.response;

import java.util.List;

/**
 * Страницы предпросмотра PNG в base64 — одним ответом через HttpClient (X-Market, §14). crowded — таблица позиций
 * тесная: кегль уменьшен ниже обычного (KpDocumentBuilder.TABLE_FONT_PT), редактор подсказывает «Альбомная».
 */
public record PreviewPagesResponse(List<String> pages, boolean crowded) {}
