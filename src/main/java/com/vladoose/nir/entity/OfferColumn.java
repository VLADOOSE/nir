package com.vladoose.nir.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Колонка таблицы КП: ключ из service.offer.OfferColumnKey + своя подпись (null — подпись по умолчанию). JSON. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferColumn {
    private String key;
    private String label;
}
