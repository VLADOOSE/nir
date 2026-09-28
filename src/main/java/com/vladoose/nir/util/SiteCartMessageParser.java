package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст, который кнопка WhatsApp на westmed.kz подставляет из корзины КП (спека whatsapp-chats §6.7):
 * приветствие → «1. Товар (x2)» построчно → просьба о КП. Шаблон — ТОЛЬКО при приветствии в начале:
 * свободный нумерованный список клиента позициями не становится («1. срочно 2. доставка»).
 */
public final class SiteCartMessageParser {

    public record Line(String name, int quantity) {}

    /** whatsappGreeting из messages/{ru,kz,en}.json сайта, в нижнем регистре. */
    private static final List<String> GREETINGS = List.of(
            "здравствуйте! интересует следующее оборудование:",
            "сәлеметсіз бе! келесі жабдық қызықтырады:",
            "hello! i'm interested in the following equipment:");

    /** «1. Облучатель ОБН-150 (x2)»; количество — до 5 цифр (больше — часть наименования, без переполнения int). */
    private static final Pattern LINE = Pattern.compile("^\\d+\\.\\s+(.+?)(?:\\s+\\(x(\\d{1,5})\\))?\\s*$");

    private SiteCartMessageParser() {}

    public static List<Line> parse(String text) {
        if (text == null) return List.of();
        String t = text.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        if (GREETINGS.stream().noneMatch(lower::startsWith)) return List.of();
        List<Line> out = new ArrayList<>();
        for (String raw : t.split("\\R")) {
            Matcher m = LINE.matcher(raw.strip());
            if (!m.matches()) continue;
            int quantity = m.group(2) == null ? 1 : Math.max(1, Integer.parseInt(m.group(2)));
            out.add(new Line(m.group(1).strip().replaceAll("\\s+", " "), quantity));
        }
        return out;
    }
}
