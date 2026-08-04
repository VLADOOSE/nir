package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Из названия/бренда лота + ТЗ строит компактный ОПИСАТЕЛЬНЫЙ текст для матчинга по комплектности.
 * Из goszakup-ТЗ берутся только значимые поля (наименование/описание/доп. описание лота + требуемые
 * характеристики), а закупочный канцелярит (номера закупки/лота, места/сроки поставки, количество,
 * адреса) выбрасывается — иначе он раздувает знаменатель score и топит процент совпадения до нечитаемого.
 * Метки в ТЗ часто разорваны переносами PDF-извлечения, поэтому пробелы сперва схлопываются.
 * ТЗ СК-Фармации меток goszakup не содержит и режется по своей шапке (см. {@link #stripSkHeader}).
 * ТЗ без тех и других (ручной/иной формат) возвращается как есть — fallback без регресса.
 */
public final class LotDescriptiveText {

    // Полный упорядоченный набор меток goszakup-ТЗ — служат границами сегментов (после схлопывания пробелов).
    private static final String[] LABELS = {
            "Номер закупки:", "Наименование закупки:", "Номер лота:", "Наименование лота:",
            "Описание лота:", "Дополнительное описание лота:", "Количество:", "Единица измерения:",
            "Места поставки:", "Место поставки:", "Срок поставки:", "характеристики закупаемых товаров:"
    };
    // Из них описательные — значение оставляем (остальные метки только режут блоб на сегменты).
    private static final Set<String> DESCRIPTIVE = Set.of(
            "Наименование лота:", "Описание лота:", "Дополнительное описание лота:",
            "характеристики закупаемых товаров:");

    private static final Pattern LABEL_ALT = Pattern.compile(
            Arrays.stream(LABELS).map(Pattern::quote).collect(Collectors.joining("|")),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // --- СК-Фармация -----------------------------------------------------------------------------
    // ТЗ с fms.ecc.kz меток goszakup не несёт, поэтому старый fallback возвращал документ целиком —
    // вместе с шапкой «Техническая спецификация / Лот <код площадки>». Код лота («4875083-Т1») —
    // это прямой адрес объявления fms.ecc.kz/ru/announce/index/<id>, то есть ровно то раскрытие
    // тендера, которое анти-лик §9 из письма убрал. Шапка устроена одинаково во всех живых ТЗ:
    //   Техническая спецификация[*] / [Лот [№] <код>] / [№ п/п] / Критерии Описание / 1 <критерий> …
    // Строка «Лот» и «№ п/п» опциональны (встречаются оба варианта), а заголовок таблицы
    // «Критерии Описание» есть всегда и стоит в начале — он и служит границей шапки.
    private static final Pattern SK_TITLE = Pattern.compile("Техническая\\s+спецификация",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SK_TABLE_HEAD = Pattern.compile("Критерии\\s+Описание",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Границу ищем только в шапке: дальше по документу «Критерии Описание» — уже реальный текст. */
    private static final int SK_HEAD_WINDOW = 400;
    /**
     * Код лота площадки: 6–9 цифр + «-Т<цифра>», с необязательной меткой «Лот»/«Лот №» перед ним.
     * Пробелы вокруг дефиса реальны («Лот  4875003 -Т1»), номер бывает и без «№», и с ним.
     */
    private static final Pattern SK_LOT_CODE = Pattern.compile(
            "(?:(?<!\\p{IsAlphabetic})Лот\\s*(?:№\\s*)?)?(?<!\\d)\\d{6,9}\\s*-\\s*[ТT]\\d+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private LotDescriptiveText() {}

    /**
     * Санитизированный текст ТЗ для тела письма поставщику: тех. характеристики без закупочного
     * канцелярита (номера закупки/лота, наименование закупки, места/сроки поставки, количество) —
     * чтобы КП не раскрывало конкретный тендер. Та же сегментация, что у {@link #forMatching}, без
     * имени/бренда. goszakup режется по меткам, СК-Фармация — по шапке, и в любом случае из результата
     * вычищается код лота площадки ({@link #scrubPlatformCodes}).
     */
    public static String requirementsForEmail(String requiredSpec) {
        return forMatching(null, null, requiredSpec);
    }

    public static String forMatching(String equipName, String manufact, String requiredSpec) {
        StringBuilder base = new StringBuilder();
        if (equipName != null && !equipName.isBlank()) base.append(equipName.trim()).append(' ');
        if (manufact != null && !manufact.isBlank()) base.append(manufact.trim()).append(' ');
        if (requiredSpec == null || requiredSpec.isBlank()) return base.toString().trim();

        String norm = requiredSpec.replaceAll("\\s+", " ").trim();
        Matcher m = LABEL_ALT.matcher(norm);
        List<int[]> bounds = new ArrayList<>(); // {valueStart, labelStart}
        List<Boolean> descriptive = new ArrayList<>();
        int prevValueStart = -1;
        while (m.find()) {
            if (prevValueStart >= 0) bounds.get(bounds.size() - 1)[1] = m.start(); // закрыть предыдущий сегмент
            bounds.add(new int[]{m.end(), norm.length()});
            descriptive.add(DESCRIPTIVE.contains(canonical(m.group())));
            prevValueStart = m.end();
        }
        // goszakup-меток нет — пробуем шапку СК-Фармации, иначе берём текст как есть (без регресса)
        if (bounds.isEmpty()) return finish(base + stripSkHeader(norm));

        StringBuilder out = new StringBuilder(base);
        boolean any = false;
        for (int i = 0; i < bounds.size(); i++) {
            if (!descriptive.get(i)) continue;
            String val = norm.substring(bounds.get(i)[0], bounds.get(i)[1]).trim();
            if (!val.isBlank()) { out.append(val).append(' '); any = true; }
        }
        return finish(any ? out.toString() : base + norm);
    }

    /**
     * Срезает шапку ТЗ СК-Фармации по заголовку таблицы требований («Критерии Описание»), оставляя
     * сами требования. Без узнаваемого заголовка документа или без заголовка таблицы в пределах шапки
     * текст не трогаем — код лота всё равно снимет {@link #scrubPlatformCodes}.
     */
    private static String stripSkHeader(String norm) {
        Matcher title = SK_TITLE.matcher(norm);
        if (!title.lookingAt()) return norm;
        Matcher head = SK_TABLE_HEAD.matcher(norm);
        if (!head.find() || head.end() > SK_HEAD_WINDOW) return norm;
        String tail = norm.substring(head.end()).trim();
        return tail.isBlank() ? norm : tail;
    }

    /**
     * Гарантия анти-лика: в исходящем тексте не остаётся кода лота площадки, какой бы ни была шапка.
     * Второй рубеж после сегментации — ловит и ТЗ с непривычной вёрсткой, и код, попавший в тело.
     */
    private static String scrubPlatformCodes(String s) {
        Matcher m = SK_LOT_CODE.matcher(s);
        return m.find() ? m.reset().replaceAll(" ") : s;
    }

    private static String finish(String s) {
        return scrubPlatformCodes(s).replaceAll("\\s+", " ").trim();
    }

    private static String canonical(String matched) {
        for (String l : LABELS) if (l.equalsIgnoreCase(matched)) return l;
        return matched;
    }
}
