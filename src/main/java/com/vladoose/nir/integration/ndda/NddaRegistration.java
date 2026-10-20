package com.vladoose.nir.integration.ndda;

import java.time.LocalDate;

/** РУ по точному номеру (RegisterService/list): действующее или нет — по тому, в каком списке нашлось. */
public record NddaRegistration(long id, String regNumber, String name, String producer, String country, LocalDate regDate,
                               LocalDate expirationDate, boolean unlimited, boolean active) {}
