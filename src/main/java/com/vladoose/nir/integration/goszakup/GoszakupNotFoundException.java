package com.vladoose.nir.integration.goszakup;

/** Ресурс не найден (HTTP 404) — отличаем «нет данных» от сбоя API. */
class GoszakupNotFoundException extends RuntimeException {
    GoszakupNotFoundException(String message) { super(message); }
}
