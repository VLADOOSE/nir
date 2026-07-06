package com.vladoose.nir.dto.response;

/** Проекция аппарата-кандидата из med_registry (native query findApparatusByTerm). */
public interface ApparatusRow {
    String getRegNumber();
    String getName();
    String getProducer();
    String getCountry();
    Long getNddaId();
}
