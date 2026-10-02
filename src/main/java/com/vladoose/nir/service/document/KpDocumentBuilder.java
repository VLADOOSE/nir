package com.vladoose.nir.service.document;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.CompanyLines;
import com.vladoose.nir.service.offer.ColumnAlign;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferColumnKey;
import com.vladoose.nir.service.offer.VatLine;
import com.vladoose.nir.util.AmountInWords;
import com.vladoose.nir.util.DocFormat;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** КП + реквизиты рынка + расчёт → KpDocument (спека §6.1–§6.3). */
@Component
public class KpDocumentBuilder {

    static final String DEFAULT_TITLE = "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ";
    static final String INCLUDED_DEFAULT = "Включено в стоимость";
    private static final Set<OfferRegistrationStatus> PRINTED_REGISTRATION =
            Set.of(OfferRegistrationStatus.CONFIRMED, OfferRegistrationStatus.NOT_REQUIRED, OfferRegistrationStatus.MANUAL);

    /** Кегль таблицы позиций, pt: offer.html (.items td) и Word — 10 pt. */
    private static final double TABLE_FONT_PT = 10;
    /**
     * Поля ячейки по горизонтали, обе стороны, мм: в PDF 2 × 1,5 (offer.html), у Word по умолчанию 2 × 108 twip ≈ 2 × 1,9 —
     * берётся большее, чтобы число влезло в обоих документах.
     */
    private static final double CELL_PADDING_MM = 3.8;
    /** Ширина набора, мм: A4 без полей страницы — @page в KpHtmlRenderer.pageCss (книжная 20/12, альбомная 15/15), у Word те же. */
    private static final double TEXT_WIDTH_PORTRAIT_MM = 210 - 20 - 12, TEXT_WIDTH_LANDSCAPE_MM = 297 - 15 - 15;

    /**
     * Сокращения, точка которых в конце условия — часть слова, а не конец фразы: «до 31.12.2026 г.», «12 мес.», «и т.д.»,
     * «50 шт.». Слово перед последней точкой сравнивается целиком и без учёта регистра; в составных после внутренней
     * точки допустим пробел («и т. д.»).
     */
    private static final List<String> TERM_ABBREVIATIONS = List.of(
            "г", "гг", "мес", "дн", "т.д", "т.п", "т.е", "т.ч", "т.к",
            "руб", "коп", "тыс", "млн", "млрд", "шт", "ед", "см", "др", "пр");
    private static final Pattern ABBREVIATION_AT_END = Pattern.compile("(?iU)(?<!\\p{L})(?:" + TERM_ABBREVIATIONS.stream()
            .map(a -> Arrays.stream(a.split("\\.")).map(Pattern::quote).collect(Collectors.joining("\\.\\s*")))
            .collect(Collectors.joining("|")) + ")$");

    public KpDocument build(ClientOffer offer, CompanyProfile profile, OfferCalculation calc) {
        String currency = profile.getMarket().currencyCode();
        List<KpDocument.Column> chosen = columns(offer.getTableColumns(), offer.isVatEnabled());
        List<KpDocument.Row> rows = rows(offer, calc, chosen);
        List<KpDocument.Column> columns = fitNumbers(chosen, rows, offer.isLandscape());
        return new KpDocument(
                offer.isLandscape(),
                new KpDocument.Letterhead(CompanyLines.split(profile.getHeaderLeft()), CompanyLines.split(profile.getHeaderRight()),
                        profile.getLogoPng(), blankToNull(profile.getBrandText()), CompanyLines.of(profile)),
                "Исх. № " + offer.getNumber() + " от " + DocFormat.date(offer.getOfferDate()) + " г.",
                CompanyLines.split(offer.getRecipient()),
                blankToNull(offer.getTitle()) == null ? DEFAULT_TITLE : offer.getTitle().trim(),
                blankToNull(offer.getSubject()),
                blankToNull(offer.getIntro()),
                offer.getTermsStyle() == TermsStyle.TABLE ? termsTable(offer.getTerms()) : List.of(),
                columns,
                rows,
                totalLines(offer, calc, currency),
                offer.isShowAmountInWords() ? "Сумма прописью: " + AmountInWords.of(calc.totals().sum(), currency) : null,
                offer.getTermsStyle() == TermsStyle.LIST ? termsList(offer.getTerms()) : List.of(),
                signoff(offer, profile));
    }

    static List<KpDocument.Column> columns(List<OfferColumn> config, boolean vat) {
        List<OfferColumnKey> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (OfferColumn c : config == null ? List.<OfferColumn>of() : config) {
            OfferColumnKey key;
            try {
                key = OfferColumnKey.parse(c.getKey());
            } catch (BadRequestException e) {
                continue;   // неизвестный ключ (старые данные) — не печатаем
            }
            if (keys.contains(key) || (!vat && key.vatOnly())) continue;
            keys.add(key);
            labels.add(blankToNull(c.getLabel()) == null ? key.defaultLabel(vat) : c.getLabel().trim());
        }
        if (!keys.contains(OfferColumnKey.NAME)) {
            keys.add(0, OfferColumnKey.NAME);
            labels.add(0, OfferColumnKey.NAME.defaultLabel(vat));
        }
        return sized(keys, labels, keys.stream().mapToInt(OfferColumnKey::weight).toArray());
    }

    /**
     * Колонки денег (выравнивание вправо) — не уже самого длинного числа в них: в ячейке число не переносится
     * (white-space: nowrap в offer.html), а «2 721 000,0 / 0» в КП клиенту недопустимо. Вес такой колонки — большее из
     * веса по умолчанию и ⌈ширина числа + поля ячейки⌉ в процентах ширины набора листа (книжного или альбомного);
     * дальше — то же сжатие, что оставляет наименованию четверть ширины. На книжном листе доли по умолчанию держат цену
     * (12 %) до 999 999,99 и сумму (13 %) до 9 999 999,99 — обычные КП ширин не меняют.
     */
    static List<KpDocument.Column> fitNumbers(List<KpDocument.Column> columns, List<KpDocument.Row> rows, boolean landscape) {
        double page = landscape ? TEXT_WIDTH_LANDSCAPE_MM : TEXT_WIDTH_PORTRAIT_MM;
        List<OfferColumnKey> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int[] weights = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            OfferColumnKey key = OfferColumnKey.parse(columns.get(i).key());
            keys.add(key);
            labels.add(columns.get(i).label());
            weights[i] = key.weight();
            if (key.align() != ColumnAlign.RIGHT) continue;
            double widest = 0;
            for (KpDocument.Row r : rows) {
                if (i >= r.cells().size()) continue;   // объединённая ячейка раздела / «включено» — не число
                for (String line : r.cells().get(i)) widest = Math.max(widest, textWidthMm(line));
            }
            weights[i] = Math.max(key.weight(), (int) Math.ceil((widest + CELL_PADDING_MM) * 100 / page));
        }
        return sized(keys, labels, weights);
    }

    /**
     * Ширина строки в ячейке таблицы позиций, мм, — оценка с запасом для чисел: цифра 0,5 em (у Liberation Serif и Times
     * New Roman ровно 0,5), любой другой знак — 0,3 em (неразрывный пробел и запятая по замеру в PDF — 0,25 em).
     */
    static double textWidthMm(String text) {
        double em = 0;
        for (int i = 0; i < text.length(); i++) em += Character.isDigit(text.charAt(i)) ? 0.5 : 0.3;
        return em * TABLE_FONT_PT * 25.4 / 72;
    }

    /**
     * Доли ширины из весов. Наименованию — остаток, но не меньше четверти ширины: в тесной таблице доли остальных
     * сжимаются до 75 % и округляются ВНИЗ (целочисленно). Округление к ближайшему поднимало их сумму до 80 % —
     * наименованию оставалось 20 %.
     */
    private static List<KpDocument.Column> sized(List<OfferColumnKey> keys, List<String> labels, int[] weights) {
        int othersRaw = 0;
        for (int i = 0; i < keys.size(); i++) if (keys.get(i) != OfferColumnKey.NAME) othersRaw += weights[i];
        int[] percent = new int[keys.size()];
        int others = 0;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) == OfferColumnKey.NAME) continue;
            percent[i] = othersRaw > 75 ? weights[i] * 75 / othersRaw : weights[i];
            others += percent[i];
        }
        List<KpDocument.Column> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            OfferColumnKey k = keys.get(i);
            int p = k == OfferColumnKey.NAME ? 100 - others : percent[i];
            result.add(new KpDocument.Column(k.name(), labels.get(i), k.align(), p));
        }
        return result;
    }

    private static List<KpDocument.Row> rows(ClientOffer offer, OfferCalculation calc, List<KpDocument.Column> columns) {
        Set<String> shown = new HashSet<>();
        columns.forEach(c -> shown.add(c.key()));
        List<KpDocument.Row> rows = new ArrayList<>();
        int number = 0;
        for (int i = 0; i < offer.getItems().size(); i++) {
            ClientOfferItem it = offer.getItems().get(i);
            switch (it.getKind()) {
                case SECTION -> rows.add(new KpDocument.Row(KpDocument.RowKind.SECTION, List.of(), 0, lines(it.getName())));
                case INCLUDED -> rows.add(included(it, columns));
                case ITEM -> {
                    number++;
                    List<List<String>> cells = new ArrayList<>();
                    for (KpDocument.Column c : columns) {
                        cells.add(cell(OfferColumnKey.parse(c.key()), it, calc.items().get(i), number, shown, offer.isDetailsInName()));
                    }
                    rows.add(new KpDocument.Row(KpDocument.RowKind.ITEM, cells, columns.size(), List.of()));
                }
            }
        }
        return rows;
    }

    private static KpDocument.Row included(ClientOfferItem it, List<KpDocument.Column> columns) {
        List<String> note = lines(blankToNull(it.getNote()) == null ? INCLUDED_DEFAULT : it.getNote());
        int nameIdx = -1;
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).key().equals(OfferColumnKey.NAME.name())) nameIdx = i;
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < nameIdx; i++) cells.add(List.of());
        List<String> name = new ArrayList<>(lines(it.getName()));
        if (nameIdx == columns.size() - 1) {   // наименование — последняя колонка: «включено» второй строкой в ней же
            name.addAll(note);
            cells.add(name);
            return new KpDocument.Row(KpDocument.RowKind.INCLUDED, cells, columns.size(), List.of());
        }
        cells.add(name);
        return new KpDocument.Row(KpDocument.RowKind.INCLUDED, cells, nameIdx + 1, note);
    }

    private static List<String> cell(OfferColumnKey key, ClientOfferItem it, ItemCalc c, int number,
                                     Set<String> shown, boolean details) {
        return switch (key) {
            case NUM -> List.of(String.valueOf(number));
            case NAME -> nameLines(it, shown, details);
            case MODEL -> lines(it.getModel());
            case MANUFACTURER -> lines(it.getManufacturer());
            case COUNTRY -> lines(it.getCountry());
            case UNIT -> lines(it.getUnit());
            case QTY -> List.of(DocFormat.qty(it.getQuantity()));
            case PRICE -> money(c.price());
            case PRICE_NET -> money(c.priceNet());
            case VAT_RATE -> List.of(DocFormat.rate(c.effectiveVatRate()));
            case VAT_SUM -> money(c.vatSum());
            case SUM_NET -> money(c.sumNet());
            case SUM -> money(c.sum());
            case REGISTRATION -> PRINTED_REGISTRATION.contains(it.getRegistrationStatus()) ? lines(it.getRegistrationText()) : List.of();
            case NOTE -> lines(it.getNote());
        };
    }

    /** Модель — через пробел после наименования, производитель и страна — второй строкой, если у них нет своих колонок. */
    private static List<String> nameLines(ClientOfferItem it, Set<String> shown, boolean details) {
        String name = it.getName() == null ? "" : it.getName().trim();
        if (details && !shown.contains(OfferColumnKey.MODEL.name()) && blankToNull(it.getModel()) != null) {
            name = name + " " + it.getModel().trim();
        }
        List<String> out = new ArrayList<>(lines(name));
        if (details) {
            boolean producer = !shown.contains(OfferColumnKey.MANUFACTURER.name()) && blankToNull(it.getManufacturer()) != null;
            boolean country = !shown.contains(OfferColumnKey.COUNTRY.name()) && blankToNull(it.getCountry()) != null;
            if (producer) out.add("Производитель: " + it.getManufacturer().trim() + (country ? ", " + it.getCountry().trim() : ""));
            else if (country) out.add("Страна: " + it.getCountry().trim());
        }
        return out;
    }

    private static List<String> totalLines(ClientOffer offer, OfferCalculation calc, String currency) {
        String cur = DocFormat.currencyShort(currency);
        List<String> lines = new ArrayList<>();
        lines.add("Итого: " + DocFormat.money(calc.totals().sum()) + " " + cur);
        if (!offer.isVatEnabled()) {
            lines.add("Без НДС");
        } else if (offer.isShowVatBreakdown()) {
            for (VatLine v : calc.totals().vat()) {
                lines.add("в т.ч. НДС " + DocFormat.rate(v.rate()) + ": " + DocFormat.money(v.amount()) + " " + cur);
            }
        }
        return lines;
    }

    private static List<KpDocument.Term> termsTable(List<OfferTerm> terms) {
        List<KpDocument.Term> out = new ArrayList<>();
        for (OfferTerm t : terms) {
            if (blankToNull(t.getValue()) == null) continue;
            out.add(new KpDocument.Term(t.getLabel() == null ? "" : t.getLabel().trim(), lines(t.getValue())));
        }
        return out;
    }

    /** «1. Цены действительны в течение 10 дней;» … последний — с точкой, как в КП отца от 24.09. */
    private static List<String> termsList(List<OfferTerm> terms) {
        List<OfferTerm> filled = terms.stream().filter(t -> blankToNull(t.getValue()) != null).toList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < filled.size(); i++) {
            OfferTerm t = filled.get(i);
            String value = withListItemEnd(String.join(" ", lines(t.getValue())), i == filled.size() - 1);
            String label = termLabel(t.getLabel());
            out.add((i + 1) + ". " + (label.isEmpty() ? value : label + ": " + value));
        }
        return out;
    }

    /**
     * Знак конца пункта: «;», у последнего — «.». Свои «.» и «;» на конце значения снимаются, кроме точки сокращения
     * ({@link #TERM_ABBREVIATIONS}): «до 31.12.2026 г.;», а последний пункт — «… г.», без второй точки.
     */
    private static String withListItemEnd(String value, boolean last) {
        String core = value.replaceAll("[;.]+$", "");
        boolean abbreviation = value.startsWith(".", core.length()) && ABBREVIATION_AT_END.matcher(core).find();
        if (abbreviation) core += ".";
        return core + (last ? (abbreviation ? "" : ".") : ";");
    }

    /** Подпись условия без своих «:» и пробелов на конце: «Порядок оплаты:» не превращается в «Порядок оплаты:: …». */
    private static String termLabel(String label) {
        return label == null ? "" : label.replaceAll("(?U)[\\s:]+$", "").trim();
    }

    private static KpDocument.Signoff signoff(ClientOffer offer, CompanyProfile profile) {
        boolean director = offer.getSignoff() == OfferSignoff.DIRECTOR;
        String shortName = profile.getShortName();
        String title = director
                ? (blankToNull(profile.getDirectorTitle()) == null ? shortName : profile.getDirectorTitle().trim() + " " + shortName)
                : shortName;
        return new KpDocument.Signoff(
                director,
                title,
                director ? surnameWithInitials(profile.getDirectorName()) : null,
                offer.isSignoffContacts() ? blankToNull(profile.getSignoffContacts()) : null,
                offer.isWithStamp() && director ? profile.getSignaturePng() : null,
                offer.isWithStamp() ? profile.getStampPng() : null,
                profile.getStampSizeMm());
    }

    /** «Ширяев Илья Викторович» → «Ширяев И. В.». */
    static String surnameWithInitials(String fullName) {
        if (blankToNull(fullName) == null) return null;
        String[] parts = fullName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length && i <= 2; i++) sb.append(' ').append(parts[i].charAt(0)).append('.');
        return sb.toString();
    }

    private static List<String> money(BigDecimal value) {
        return List.of(value == null ? "—" : DocFormat.money(value));
    }

    private static List<String> lines(String text) {
        return CompanyLines.split(text);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
