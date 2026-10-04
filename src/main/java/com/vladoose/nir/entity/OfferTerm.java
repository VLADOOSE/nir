package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Условие КП «название — значение»; пустое название — в списке печатается только значение. JSON. Поле, которого класс
 * не знает (его записала бы новая версия перед откатом), при чтении пропускается — иначе КП и реквизиты с такой
 * JSON-колонкой не загрузились бы вовсе.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OfferTerm {
    private String label;
    private String value;
}
