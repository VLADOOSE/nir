package com.vladoose.nir.dto.response;

import java.util.List;

/** Этапы сделки и «Дальше» (спека 2026-10-18 §3.1, §4.1). state — DONE | CURRENT | TODO | SKIPPED; action == null — без кнопки. */
public record DealProgress(List<Step> steps, Next next) {
    public record Step(String key, String state, String label, String detail) {}

    /** hintId — файл переписки подсказки «КП ушло клиенту» (спека 2026-10-19 §3.4); у остальных «Дальше» — пусто. */
    public record Next(String text, String action, Long refId, Long hintId) {
        public Next(String text, String action, Long refId) {
            this(text, action, refId, null);
        }
    }
}
