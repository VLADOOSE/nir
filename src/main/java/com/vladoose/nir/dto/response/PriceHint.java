package com.vladoose.nir.dto.response;

import java.util.List;

/** Подсказка «📒 по прайсу» (спека §6.1): строки текущих прайсов поставщиков — лучшая первой; пусто — подсказки нет. */
public record PriceHint(String text, List<PriceHintRow> rows) {}
