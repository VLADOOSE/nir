package com.vladoose.nir.integration.lead;

import com.vladoose.nir.entity.LeadStatus;

/** Коды источников обращений и соответствие статусов АИС статусам сайта (спека §5). */
public final class LeadSources {

    public static final String WESTMED = "westmed.kz";
    public static final String MANUAL = "manual";
    public static final String WHATSAPP = "whatsapp";
    public static final String EMAIL = "email";

    private LeadSources() {}

    /** Источники, куда АИС пишет статус обратно. Сейчас — только westmed.kz. */
    public static boolean writesBack(String source) {
        return WESTMED.equals(source);
    }

    /** Подпись источника в ленте обращения: «westmed.kz», «WhatsApp», «почта»… (у ручного ввода своя подпись). */
    public static String label(String source) {
        if (WHATSAPP.equals(source)) return "WhatsApp";
        if (EMAIL.equals(source)) return "почта";
        return source;
    }

    /** Статус обращения → статус заявки на сайте (NEW / PROCESSED / CLOSED). */
    public static String siteStatusFor(LeadStatus status) {
        return switch (status) {
            case NEW -> "NEW";
            case IN_WORK, CONVERTED -> "PROCESSED";
            case CLOSED -> "CLOSED";
        };
    }

    /** Подпись статуса сайта — как в его админке. */
    public static String siteStatusLabel(String siteStatus) {
        if (siteStatus == null) return "—";
        return switch (siteStatus) {
            case "NEW" -> "Новая";
            case "PROCESSED" -> "В работе";
            case "CLOSED" -> "Закрыта";
            default -> siteStatus;
        };
    }
}
