package com.vladoose.nir.service.productwatch;

/** Лот для правил: текст площадки и разобранное ТЗ (null — ТЗ ещё нет). */
public record LotInput(long lotId, String name, String description, String spec) {}
