package com.vladoose.nir.service;

import com.vladoose.nir.entity.Tender;

/**
 * Кто клиент для поставщика и строка о конечном заказчике (спека 2026-10-12 §6.2, §6.4). Клиент — учреждение или
 * название клиента заявки; контактное лицо и телефон поставщику не раскрываются (персональные данные, «закрепить проект»
 * за ними нельзя).
 */
public final class RfqClientLine {

    private RfqClientLine() {}

    /** Название клиента одной строкой; нет ни учреждения, ни названия — null. */
    public static String clientName(Tender t) {
        if (t.getFacility() != null && notBlank(t.getFacility().getName())) return oneLine(t.getFacility().getName());
        if (notBlank(t.getCustomerName())) return oneLine(t.getCustomerName());
        return null;
    }

    /** Строка по шаблону (пусто — зашитый) или null, если клиента в заявке нет. */
    public static String render(String template, Tender t, String company) {
        String client = clientName(t);
        if (client == null) return null;
        String place = notBlank(t.getDeliveryAddress()) ? ", место поставки: " + oneLine(t.getDeliveryAddress()) : "";
        String tpl = notBlank(template) ? template : RfqTexts.DEFAULT_CLIENT_LINE;
        return oneLine(tpl.replace("{{клиент}}", client).replace("{{место}}", place)
                .replace("{{компания}}", company == null ? "" : company));
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
