package com.vladoose.nir.dto.response;

import java.util.List;

/** Кандидаты связи строки прайса (спека §5.3): наши товары (KZ) и позиции каталога рынка. */
public record PriceLinkCandidates(List<LotModelSearchResponse.Our> products, List<EquipmentSearchItemResponse> equipment) {}
