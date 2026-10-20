package com.vladoose.nir.service.productwatch.fit;

import java.util.List;

/**
 * Требование ТЗ (спека §5): текст пункта (для экрана), имя параметра (нормализованное), вид, условие, границы в базовых
 * единицах, единица (null — счётное или признак), габариты по убыванию.
 */
public record SpecRequirement(String text, String name, Kind kind, SpecCmp cmp, double lo, double hi, SpecUnit unit,
                              List<Double> dims) {
    public enum Kind { NUMBER, COUNT, DIMS, FEATURE }
}
