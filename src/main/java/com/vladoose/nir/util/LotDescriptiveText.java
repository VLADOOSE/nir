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
 * ТЗ без этих меток (ручной/иной формат) возвращается как есть — fallback без регресса.
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

    private LotDescriptiveText() {}

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
        if (bounds.isEmpty()) return (base + norm).trim(); // меток нет — иной формат ТЗ, без регресса

        StringBuilder out = new StringBuilder(base);
        boolean any = false;
        for (int i = 0; i < bounds.size(); i++) {
            if (!descriptive.get(i)) continue;
            String val = norm.substring(bounds.get(i)[0], bounds.get(i)[1]).trim();
            if (!val.isBlank()) { out.append(val).append(' '); any = true; }
        }
        return any ? out.toString().trim() : (base + norm).trim();
    }

    private static String canonical(String matched) {
        for (String l : LABELS) if (l.equalsIgnoreCase(matched)) return l;
        return matched;
    }
}
