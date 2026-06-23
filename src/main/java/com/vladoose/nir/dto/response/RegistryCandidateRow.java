package com.vladoose.nir.dto.response;

import java.time.LocalDate;

/** Проекция результата native trgm-запроса кандидатов реестра. */
public interface RegistryCandidateRow {
    String getRegNumber();
    String getName();
    String getProducer();
    String getCountry();
    LocalDate getRegDate();
    LocalDate getExpirationDate();
    Boolean getUnlimited();
    Double getScore();
}
