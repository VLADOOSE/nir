package com.vladoose.nir.service.productwatch.fit;

import java.util.List;

/** Факт нашего товара: имя параметра (нормализованное), условие и границы в базовых единицах, откуда, исходный текст. */
public record ProductFact(String name, SpecCmp cmp, double lo, double hi, SpecUnit unit, List<Double> dims, String source,
                          String text) {}
