package com.vladoose.nir.dto.response;

import java.util.List;

/** Этапы сделки и «Дальше» (спека 2026-10-18 §3.1, §4.1). state — DONE | CURRENT | TODO | SKIPPED; action == null — без кнопки. */
public record DealProgress(List<Step> steps, Next next) {
    public record Step(String key, String state, String label, String detail) {}
    public record Next(String text, String action, Long refId) {}
}
