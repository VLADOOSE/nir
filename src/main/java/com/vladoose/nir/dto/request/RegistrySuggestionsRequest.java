package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Строки КП для подсказок РУ (спека 2026-10-13 §2.3): ключ строки экрана, наименование, модель, производитель. */
public record RegistrySuggestionsRequest(@NotNull @Size(max = 500) List<@Valid Row> rows) {
    public record Row(@Size(max = 64) String key, @Size(max = 4000) String name, @Size(max = 255) String model,
                      @Size(max = 500) String manufacturer) {}
}
