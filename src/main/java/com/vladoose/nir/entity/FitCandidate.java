package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Сверка лота с одним нашим товаром (лежит в JSONB отметки — `fit_candidates`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FitCandidate(long productId, String name, String slug, FitVerdict verdict, int met, int failed, int total,
                           int unknown, String reason, String reasonShort, String regStatus, String regNumber, List<FitRow> rows) {}
