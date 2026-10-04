package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Колонка таблицы КП: ключ из service.offer.OfferColumnKey + своя подпись (null — подпись по умолчанию). JSON. Поле,
 * которого класс не знает (его записала бы новая версия перед откатом), при чтении пропускается — иначе КП и реквизиты
 * с такой JSON-колонкой не загрузились бы вовсе.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OfferColumn {
    private String key;
    private String label;
}
