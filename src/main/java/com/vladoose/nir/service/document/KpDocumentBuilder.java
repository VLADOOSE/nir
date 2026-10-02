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
import java.util.EnumSet;
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

    // Ширины колонок и кегль таблицы позиций (fitTable) — все числа здесь.
    /** Кегль таблицы позиций, pt: обычный (им печатается всё, что помещается), нижний предел подбора, шаг подбора. */
    private static final double TABLE_FONT_PT = 10, MIN_TABLE_FONT_PT = 8, TABLE_FONT_STEP_PT = 0.5;
    /** Сумма долей всех колонок, кроме наименования, %: наименованию — не меньше четверти ширины. */
    private static final double OTHERS_MAX_PERCENT = 75;
    /**
     * Поля ячейки по горизонтали, обе стороны, мм: в PDF 2 × 1,5 (offer.html), у Word по умолчанию 2 × 108 twip ≈ 2 × 1,9 —
     * берётся большее, чтобы число влезло в обоих документах.
     */
    private static final double CELL_PADDING_MM = 3.8;
    /** Самая узкая колонка — поля и одна буква: самые широкие буквы (Ш, Щ, Ю, М, W) — около 1 em. */
    private static final double ONE_GLYPH_EM = 1.0;
    /**
     * Ширина набора, мм: A4 без полей страницы — @page в KpHtmlRenderer.pageCss (книжная 20/12, альбомная 15/15), у Word
     * те же. Веса колонок по умолчанию (OfferColumnKey.weight) — доли книжного листа: на альбомном та же колонка в
     * миллиметрах — меньшая доля, поэтому тесноту таблица меряет в миллиметрах.
     */
    private static final double TEXT_WIDTH_PORTRAIT_MM = 210 - 20 - 12, TEXT_WIDTH_LANDSCAPE_MM = 297 - 15 - 15;
    private static final double MM_PER_PT = 25.4 / 72;
    /** Короткие колонки — одно «слово» в ячейке (номер, количество, единица, ставка): ширина по содержимому, без переноса. */
    private static final Set<OfferColumnKey> SHORT_COLUMNS =
            EnumSet.of(OfferColumnKey.NUM, OfferColumnKey.QTY, OfferColumnKey.UNIT, OfferColumnKey.VAT_RATE);
    private static final double EPS = 1e-9;

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
        TableFit table = fitTable(chosen, rows, offer.isLandscape());
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
                table.columns(),
                rows,
                totalLines(offer, calc, currency),
                offer.isShowAmountInWords() ? "Сумма прописью: " + AmountInWords.of(calc.totals().sum(), currency) : null,
                offer.getTermsStyle() == TermsStyle.LIST ? termsList(offer.getTerms()) : List.of(),
                signoff(offer, profile),
                table.fontPt());
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
        return sized(keys, labels, new boolean[keys.size()], keys.stream().mapToDouble(OfferColumnKey::weight).toArray());
    }

    /** Колонки таблицы позиций с долями ширины и кегль таблицы. */
    private record TableFit(List<KpDocument.Column> columns, double fontPt) {}

    /** Как колонка получает ширину: наименование — остаток; деньги и короткие — по содержимому, без переноса; текст — по весу. */
    private enum Fit { NAME, MONEY, SHORT, TEXT }

    /**
     * Ширины колонок и кегль таблицы. Деньги и короткие колонки (№, кол-во, ед. изм., ставка и любая колонка, где в каждой
     * ячейке одно «слово») не переносятся — их колонка не уже самого длинного содержимого; любая колонка — не уже полей
     * и одной буквы; остальные колонки (кроме наименования) — по весу по умолчанию.
     * <ul>
     * <li>Теснота — в миллиметрах: всё, кроме наименования, на книжном листе должно влезть в 75 % ширины; на альбомном те
     * же колонки в миллиметрах занимают меньшую долю — таблица, которой хватает 267 мм, остаётся в обычном кегле.</li>
     * <li>Тесно — таблица мельчает: от 10 до 8 pt шагом 0,5 pt; с кеглем пропорционально уменьшаются веса по умолчанию
     * (мельче буквы — меньше места), содержимое считается заново. Первый подошедший кегль — ответ.</li>
     * <li>Доли не влезают в 75 % (на альбомном листе — проценты те же, что на книжном, а физически места хватает; или
     * не хватило и 8 pt) — сжатие: деньги — ровно по нужде, остальные — пропорционально весам, но не ниже своего минимума.
     * Минимумы не влезают и в 8 pt (абсурдно тесная таблица) — прежнее пропорциональное сжатие всех колонок.</li>
     * </ul>
     * Обычные КП не меняются: 10 pt и доли по умолчанию держат цену (12 %) до 999 999,99 и сумму (13 %) до 9 999 999,99.
     */
    private static TableFit fitTable(List<KpDocument.Column> columns, List<KpDocument.Row> rows, boolean landscape) {
        double page = landscape ? TEXT_WIDTH_LANDSCAPE_MM : TEXT_WIDTH_PORTRAIT_MM;
        int n = columns.size();
        List<OfferColumnKey> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Fit[] fit = new Fit[n];
        double[] widestEm = new double[n];   // самая длинная строка ячейки колонки, em
        for (int i = 0; i < n; i++) {
            OfferColumnKey key = OfferColumnKey.parse(columns.get(i).key());
            keys.add(key);
            labels.add(columns.get(i).label());
            boolean filled = false, singleWords = true;
            for (KpDocument.Row r : rows) {
                if (i >= r.cells().size()) continue;   // объединённая ячейка раздела / «включено» — не своя
                for (String line : r.cells().get(i)) {
                    if (line.isBlank()) continue;
                    filled = true;
                    singleWords &= line.trim().indexOf(' ') < 0;   // переносится только по обычному пробелу (NBSP — нет)
                    widestEm[i] = Math.max(widestEm[i], textEm(line));
                }
            }
            fit[i] = key == OfferColumnKey.NAME ? Fit.NAME
                    : key.align() == ColumnAlign.RIGHT ? Fit.MONEY
                    : SHORT_COLUMNS.contains(key) || (filled && singleWords) ? Fit.SHORT : Fit.TEXT;
        }
        boolean[] nowrap = new boolean[n];
        for (int i = 0; i < n; i++) nowrap[i] = fit[i] == Fit.MONEY || fit[i] == Fit.SHORT;
        for (double pt = TABLE_FONT_PT; pt >= MIN_TABLE_FONT_PT; pt -= TABLE_FONT_STEP_PT) {
            double[] physical = weightsAt(keys, fit, widestEm, pt, page, TEXT_WIDTH_PORTRAIT_MM / page);
            if (others(keys, physical) > OTHERS_MAX_PERCENT + EPS) continue;   // тесно в миллиметрах — мельче
            double[] fitted = squeeze(fit, weightsAt(keys, fit, widestEm, pt, page, 1), minimums(fit, widestEm, pt, page));
            if (fitted != null) return new TableFit(sized(keys, labels, nowrap, fitted), pt);
        }
        double[] weights = weightsAt(keys, fit, widestEm, MIN_TABLE_FONT_PT, page, 1);
        double[] fitted = squeeze(fit, weights, minimums(fit, widestEm, MIN_TABLE_FONT_PT, page));
        return new TableFit(sized(keys, labels, nowrap, fitted != null ? fitted : weights), MIN_TABLE_FONT_PT);
    }

    /**
     * Веса колонок при кегле pt, % ширины набора: вес по умолчанию × кегль / 10 × scale (scale — доля книжного листа в
     * текущем: проверка тесноты в миллиметрах; 1 — доли раскладки), но не меньше минимума колонки.
     */
    private static double[] weightsAt(List<OfferColumnKey> keys, Fit[] fit, double[] widestEm, double pt, double page, double scale) {
        double[] minimum = minimums(fit, widestEm, pt, page);
        double[] weights = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            if (fit[i] != Fit.NAME) weights[i] = Math.max(keys.get(i).weight() * pt / TABLE_FONT_PT * scale, minimum[i]);
        }
        return weights;
    }

    /**
     * Минимум колонки при кегле pt, % ширины набора (целый — округление его не урежет): поля ячейки и самое длинное
     * содержимое у денег и коротких колонок, у любой колонки — не меньше полей и одной буквы.
     */
    private static double[] minimums(Fit[] fit, double[] widestEm, double pt, double page) {
        double[] minimum = new double[fit.length];
        for (int i = 0; i < fit.length; i++) {
            if (fit[i] == Fit.NAME) continue;
            double em = fit[i] == Fit.MONEY || fit[i] == Fit.SHORT ? Math.max(widestEm[i], ONE_GLYPH_EM) : ONE_GLYPH_EM;
            minimum[i] = Math.ceil((em * pt * MM_PER_PT + CELL_PADDING_MM) * 100 / page - EPS);
        }
        return minimum;
    }

    /**
     * Сжатие в 75 %: деньги — ровно по нужде (не по весу по умолчанию), остальные колонки делят оставшееся пропорционально
     * своим весам, но не ниже минимума: упёршаяся в минимум остаётся на нём, остальные делят остаток заново. null —
     * минимумы не влезают.
     */
    private static double[] squeeze(Fit[] fit, double[] weights, double[] minimum) {
        double total = 0;
        for (int i = 0; i < fit.length; i++) if (fit[i] != Fit.NAME) total += weights[i];
        if (total <= OTHERS_MAX_PERCENT + EPS) return weights;
        double[] squeezed = new double[fit.length];
        double rest = OTHERS_MAX_PERCENT;
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < fit.length; i++) {
            if (fit[i] == Fit.MONEY) {
                squeezed[i] = minimum[i];
                rest -= minimum[i];
            } else if (fit[i] != Fit.NAME) {
                free.add(i);
            }
        }
        while (rest >= -EPS) {
            double sum = 0;
            for (int i : free) sum += weights[i];
            double share = sum > 0 ? Math.min(1, rest / sum) : 1;
            List<Integer> stuck = new ArrayList<>();
            for (int i : free) if (weights[i] * share < minimum[i] - EPS) stuck.add(i);
            if (stuck.isEmpty()) {
                for (int i : free) squeezed[i] = weights[i] * share;
                return squeezed;
            }
            for (int i : stuck) {
                squeezed[i] = minimum[i];
                rest -= minimum[i];
            }
            free.removeAll(stuck);
        }
        return null;
    }

    private static double others(List<OfferColumnKey> keys, double[] weights) {
        double sum = 0;
        for (int i = 0; i < keys.size(); i++) if (keys.get(i) != OfferColumnKey.NAME) sum += weights[i];
        return sum;
    }

    /**
     * Ширина строки в ячейке таблицы позиций, em, — оценка с запасом (метрика Liberation Serif = Times New Roman): цифра —
     * 0,5 (ровно); пробел, неразрывный пробел, запятая, точка, двоеточие — 0,3 (по замеру 0,25); «%» — 0,85 (0,83);
     * заглавная буква — 0,8 (обычно 0,6–0,75); прочее (строчные буквы, знаки) — 0,6 (обычно 0,4–0,55).
     */
    static double textEm(String text) {
        double em = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            em += Character.isDigit(c) ? 0.5
                    : Character.isSpaceChar(c) || c == ',' || c == '.' || c == ':' ? 0.3
                    : c == '%' ? 0.85
                    : Character.isUpperCase(c) ? 0.8
                    : 0.6;
        }
        return em;
    }

    /**
     * Доли ширины из весов: всё, кроме наименования, — в пределах 75 % (больше — пропорциональное сжатие: так бывает лишь
     * у абсурдно тесной таблицы), наименованию — остаток, не меньше четверти. Целые проценты — округлением наибольших
     * остатков: сумма 100, ни одна колонка не теряет систематически (при округлении вниз всё срезанное доставалось
     * наименованию, а узкие колонки теряли до трети ширины: 2,98 % → 2 %).
     */
    private static List<KpDocument.Column> sized(List<OfferColumnKey> keys, List<String> labels, boolean[] nowrap, double[] weights) {
        int n = keys.size();
        double othersRaw = others(keys, weights);
        double scale = othersRaw > OTHERS_MAX_PERCENT + EPS ? OTHERS_MAX_PERCENT / othersRaw : 1;
        double[] exact = new double[n];
        double others = 0;
        int name = -1;
        for (int i = 0; i < n; i++) {
            if (keys.get(i) == OfferColumnKey.NAME) {
                name = i;
                continue;
            }
            exact[i] = weights[i] * scale;
            others += exact[i];
        }
        exact[name] = 100 - others;
        int[] percent = new int[n];
        int sum = 0;
        for (int i = 0; i < n; i++) {
            percent[i] = (int) Math.floor(exact[i] + EPS);
            sum += percent[i];
        }
        Integer[] byRemainder = new Integer[n];
        for (int i = 0; i < n; i++) byRemainder[i] = i;
        Arrays.sort(byRemainder, (a, b) -> Double.compare(exact[b] - percent[b], exact[a] - percent[a]));
        for (int k = 0; k < 100 - sum; k++) percent[byRemainder[k]]++;
        List<KpDocument.Column> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            OfferColumnKey k = keys.get(i);
            result.add(new KpDocument.Column(k.name(), labels.get(i), k.align(), percent[i], nowrap[i]));
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
