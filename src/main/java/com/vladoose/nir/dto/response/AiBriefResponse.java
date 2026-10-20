package com.vladoose.nir.dto.response;

/** «📋 Для ИИ» по списку (спека 2026-10-20-product-spec-fit §9): текст, сколько тендеров вошло и сколько просили. */
public record AiBriefResponse(String text, int tenders, int total) {}
