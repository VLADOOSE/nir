package com.vladoose.nir.service;

import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;

import java.util.Arrays;
import java.util.List;

/**
 * Тексты запроса на бланке и строка о конечном заказчике (спека 2026-10-12 §6.4): сохранённые рынка или зашитые.
 * Строка о заказчике общая для письма ({{заказчик}}) и документа. В вводной и строке — {{компания}}, в строке ещё
 * {{клиент}} и {{место}}.
 */
public record RfqTexts(String title, String intro, List<String> ask, String clientLine) {

    public static final String DEFAULT_TITLE = "ЗАПРОС КОММЕРЧЕСКОГО ПРЕДЛОЖЕНИЯ";
    public static final String DEFAULT_INTRO =
            "{{компания}} просит предоставить коммерческое предложение на поставку следующих товаров:";
    public static final String DEFAULT_CLIENT_LINE =
            "Конечный заказчик: {{клиент}}{{место}}. Просим закрепить проект за {{компания}}.";

    /** Пункты «В предложении просим указать», по одному на строку; регистрация — реестра своего рынка. */
    public static String defaultAsk(Market market) {
        String registration = market == Market.KZ
                ? "Номер РУ РК и сертификат соответствия (СТ-KZ / СТ-1) — при наличии"
                : "Номер регистрационного удостоверения Росздравнадзора";
        return String.join("\n", "Модель и производитель", "Цена за единицу с НДС и срок действия цены",
                "Срок поставки и наличие", registration, "Гарантийный срок", "Условия доставки");
    }

    public static RfqTexts of(EmailTemplate saved, Market market) {
        return new RfqTexts(
                or(saved == null ? null : saved.getRfqTitle(), DEFAULT_TITLE).strip(),
                or(saved == null ? null : saved.getRfqIntro(), DEFAULT_INTRO).strip(),
                lines(or(saved == null ? null : saved.getRfqAsk(), defaultAsk(market))),
                or(saved == null ? null : saved.getRfqClientLine(), DEFAULT_CLIENT_LINE).strip());
    }

    /** Что хранить: пусто или совпадает с зашитым — null (тогда поправка зашитого текста дойдёт и до этого рынка). */
    public static String custom(String typed, String byDefault) {
        if (typed == null || typed.isBlank()) return null;
        return String.join("\n", lines(typed)).equals(String.join("\n", lines(byDefault))) ? null : typed.strip();
    }

    /** Строки текста без пустых и пробелов по краям. */
    public static List<String> lines(String text) {
        if (text == null) return List.of();
        return Arrays.stream(text.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String or(String value, String byDefault) {
        return value == null || value.isBlank() ? byDefault : value;
    }
}
