package com.vladoose.nir.service.deal;

/** Подписи статусов заявки на участие — как на «Заявках на участие» (shared/apply-labels.ts). */
public final class ApplyStatusLabels {
    private ApplyStatusLabels() {}

    public static String label(String status) {
        if (status == null) return "—";
        return switch (status) {
            case "DRAFT" -> "черновик";
            case "SUBMITTED" -> "подана";
            case "UNDER_REVIEW" -> "на рассмотрении";
            case "WON" -> "выиграли";
            case "LOST" -> "проиграли";
            case "CANCELLED" -> "отменена";
            case "REJECTED" -> "отклонена";
            default -> status;
        };
    }
}
