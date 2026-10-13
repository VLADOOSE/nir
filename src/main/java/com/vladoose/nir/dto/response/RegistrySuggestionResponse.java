package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.OfferRegistrationStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Подсказка для строки КП: SUGGESTED — № и запись реестра, NOT_FOUND — подходящей записи нет. */
public record RegistrySuggestionResponse(String key, OfferRegistrationStatus status, String regNumber, BigDecimal score,
                                         RegistryCandidateResponse registry) {

    public static RegistrySuggestionResponse suggested(String key, RegistryCandidateResponse c) {
        BigDecimal score = c.getScore() == null ? null : BigDecimal.valueOf(c.getScore()).setScale(3, RoundingMode.HALF_UP);
        return new RegistrySuggestionResponse(key, OfferRegistrationStatus.SUGGESTED, c.getRegNumber(), score, c);
    }

    public static RegistrySuggestionResponse notFound(String key) {
        return new RegistrySuggestionResponse(key, OfferRegistrationStatus.NOT_FOUND, null, null, null);
    }
}
