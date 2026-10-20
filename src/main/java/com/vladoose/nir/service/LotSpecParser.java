package com.vladoose.nir.service;

/** Разбор ТЗ одного лота: в работе — {@link TechSpecLotParser}, в тестах — лямбда. */
@FunctionalInterface
public interface LotSpecParser {
    TechSpecLotParser.Result parse(Long lotId);
}
