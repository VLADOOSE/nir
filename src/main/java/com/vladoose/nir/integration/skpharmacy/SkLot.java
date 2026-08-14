package com.vladoose.nir.integration.skpharmacy;

import java.math.BigDecimal;

/**
 * Лот объявления СК-Фармации (вкладка lots).
 *
 * <p>{@code description} — колонка-описание таблицы лотов («Характеристика» / «Лекарственная форма»):
 * у объявлений новых вёрсток PDF техспеки нет вообще, и это ЕДИНСТВЕННЫЙ источник характеристик изделия
 * (состав набора, размеры, принцип анализа — медиана 354–631 символ). Может быть пустой: у лекарств там
 * лежит просто форма выпуска («Таблетки 8 мг»), а в вёрстке медтехники колонки нет вовсе.
 */
public record SkLot(String code, String name, BigDecimal unitPrice, Integer quantity, String description) {}
