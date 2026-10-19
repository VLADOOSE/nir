package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Строка КП из файла (спека 2026-10-19 §4.2): вид, наименование, модель (артикул), производитель (бренд), количество,
 * единица, цена клиенту за единицу с НДС, как в документе (null — нет или валюта не та), текст колонки НДС.
 */
public record OfferFileLine(ClientOfferItemKind kind, String name, String model, String brand, BigDecimal quantity,
                            String unit, BigDecimal price, String vat) {

    /** Наименование, модель и бренд — то, что сравнивается с позицией заявки. */
    public String matchText() {
        List<String> parts = new ArrayList<>();
        for (String p : new String[]{name, model, brand}) if (p != null && !p.isBlank()) parts.add(p);
        return String.join(" ", parts);
    }
}
