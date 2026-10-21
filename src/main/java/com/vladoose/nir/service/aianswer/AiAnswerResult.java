package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.entity.AiVerdict;

import java.util.List;

/** Итог разбора ответа ИИ (спека 2026-10-21-tender-telegram-bot §6): по лотам, нераспознанное, письма, пометки. */
public record AiAnswerResult(List<LotResult> lots, List<String> unparsed, List<LetterPlan> letters, List<String> notes) {

    public boolean isEmpty() { return lots.isEmpty() && unparsed.isEmpty(); }

    public enum Outcome { PINNED, SAME_MODEL, KEPT_MANUAL, ADVICE, NOT_OURS, UNSURE, CLOSED, LOT_NOT_FOUND, PRODUCT_NOT_CANDIDATE, PRODUCT_REMOVED }

    /** text — готовая строка для бота и экрана («✅ «Дефибриллятор» · ГБ №7 → ДКИ-Н-12 — закреплено»). */
    public record LotResult(long lotId, Long tenderId, String tenderNumber, String lotName, String customer, AiVerdict verdict,
                            Outcome outcome, String model, String text) {}

    /** Письмо поставщику по лотам одного тендера: завод бренда модели или «Доверенный». */
    public record LetterPlan(long tenderId, String tenderNumber, String customer, long distributorId, String distributorName,
                             boolean manufacturer, List<Long> lotIds, List<String> lotNames) {}
}
