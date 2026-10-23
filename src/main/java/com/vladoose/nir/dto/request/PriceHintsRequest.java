package com.vladoose.nir.dto.request;

import java.util.List;

/** Подсказки по текстам позиций (до 200). */
public record PriceHintsRequest(List<String> texts) {}
