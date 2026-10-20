package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.RegStatus;

import java.time.LocalDate;
import java.util.List;

/** РУ товара из `product_registration` — без JPA (снимок правил). */
public record RegistrationInfo(String regNumber, RegStatus status, LocalDate expires, boolean unlimited, String purpose,
                               String useArea, String techChars, List<String> components) {}
