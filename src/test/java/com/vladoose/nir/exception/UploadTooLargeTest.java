package com.vladoose.nir.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Файл больше предела загрузки — 400 с понятным текстом, а не 500 «Внутренняя ошибка сервера» (скан КП поставщика на
 * 12 МБ через «Загрузить файл» — обычный случай). MockMvc предел не проверяет — его проверяет Tomcat, поэтому проверяется
 * сам обработчик.
 */
class UploadTooLargeTest {

    @Test
    void tooLargeUploadIs400WithLimitInText() {
        ResponseEntity<ApiError> r = new GlobalExceptionHandler().handleTooLarge(new MaxUploadSizeExceededException(15L * 1024 * 1024));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().getMessage()).isEqualTo("Файл больше 15 МБ — уменьшите его и загрузите снова");
        // разбор Tomcat кладёт размер −1 (StandardMultipartHttpServletRequest.handleParseFailure) — текст по пределу настройки
        assertThat(new GlobalExceptionHandler().handleTooLarge(new MaxUploadSizeExceededException(-1)).getBody().getMessage())
                .isEqualTo("Файл больше 15 МБ — уменьшите его и загрузите снова");
    }
}
