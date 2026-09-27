package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Spring Page сайта (сериализуется «как есть»: content, last, totalPages, number, …). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedPage<T>(List<T> content, Boolean last, Integer totalPages, Integer number) {

    public List<T> contentOrEmpty() {
        return content == null ? List.of() : content;
    }

    /** Флаг last, иначе по номеру и числу страниц; данных нет — считаем последней (не зациклиться). */
    public boolean isLast() {
        if (last != null) return last;
        if (number != null && totalPages != null) return number >= totalPages - 1;
        return true;
    }
}
