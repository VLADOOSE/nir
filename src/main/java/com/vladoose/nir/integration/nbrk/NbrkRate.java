package com.vladoose.nir.integration.nbrk;

import java.math.BigDecimal;

/** Официальный курс НБ РК: тенге за 1 единицу валюты (у драма Нацбанк пишет «за 10» — делится при разборе). */
public record NbrkRate(String currency, BigDecimal rate) {}
