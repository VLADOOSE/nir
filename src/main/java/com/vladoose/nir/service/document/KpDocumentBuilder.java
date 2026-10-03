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
    /**
     * Обычный кегль таблицы позиций, pt: им печатается всё, что помещается. KpDocument.tableFontPt ниже него — таблица
     * тесная (предпросмотр сообщает это редактору: тот подсказывает «Альбомная»).
     */
    public static final double TABLE_FONT_PT = 10;
    /** Нижний предел и шаг подбора кегля тесной таблицы, pt. */
    private static final double MIN_TABLE_FONT_PT = 8, TABLE_FONT_STEP_PT = 0.5;
    /** Сумма долей всех колонок, кроме наименования, %: наименованию — не меньше четверти ширины. */
    private static final double OTHERS_MAX_PERCENT = 75;
    /**
     * Поля ячейки в нужде денег и коротких колонок, обе стороны, мм: поля ячейки 2 × KpPageGeometry.CELL_PADDING_H_MM
     * (3,0 — одни и те же в PDF и в Word) и 0,8 мм запаса. Запас остался от полей Word по умолчанию (2 × 108 twip ≈
     * 2 × 1,9 мм), под которые число считалось раньше: с ним ширины колонок не сдвинулись, а оценка ширины числа держит
     * запас и на округление долей.
     */
    private static final double CELL_PADDING_MM = 3.8;
    /** Самая узкая колонка денег и короткая — поля и одна буква: самые широкие буквы (Ш, Щ, Ю, М, W) — около 1 em. */
    private static final double ONE_GLYPH_EM = 1.0;
    /**
     * Текстовую колонку сжатие (шаги 2–3 подбора) не уводит уже полей и четырёх букв: четыре буквы средней ширины — 2 em
     * (строчная кириллица Liberation Serif по частотам букв — 0,498 em), поля — ровно поля ячейки, 2 × 1,5 мм
     * (KpPageGeometry: одни в PDF и в Word). Текст переносится и за ячейку не выходит, поэтому запаса здесь нет; с запасом
     * CELL_PADDING_MM книжная таблица в 11 колонок с миллионами не влезала бы и в шаг 3.
     */
    private static final double TEXT_MIN_EM = 4 * 0.5, TEXT_PADDING_MM = 2 * KpPageGeometry.CELL_PADDING_H_MM;
    /** Наименованию — не меньше четверти ширины, в крайнем случае (шаг 3 подбора) — пятой части. */
    private static final double NAME_MIN_PERCENT = 25, NAME_MIN_SQUEEZED_PERCENT = 20;
    /**
     * Ширина набора, мм: A4 без полей страницы — KpPageGeometry (книжная 20/12, альбомная 15/15; те же поля у @page PDF и
     * у Word). Веса колонок по умолчанию (OfferColumnKey.weight) — доли книжного листа: на альбомном та же колонка в
     * миллиметрах — меньшая доля, поэтому тесноту таблица меряет в миллиметрах.
     */
    private static final double TEXT_WIDTH_PORTRAIT_MM = KpPageGeometry.textWidthMm(false),
            TEXT_WIDTH_LANDSCAPE_MM = KpPageGeometry.textWidthMm(true);
    private static final double MM_PER_PT = 25.4 / 72;
    /**
     * Классы колонок — по ключу (classify). Деньги (выравнивание вправо) и количество — числа: не переносятся никогда,
     * колонка — по самому длинному. № — так же. Ед. изм. и ставка НДС не переносятся, пока строка с полями не шире
     * FLEX_MAX_SHARES своих долей по умолчанию; длиннее — переносятся по пробелам. Остальные — текст: переносятся всегда
     * (длинный код модели без пробелов — внутри своей ячейки, спека §6.4).
     */
    private static final Set<OfferColumnKey> FLEX_COLUMNS = EnumSet.of(OfferColumnKey.UNIT, OfferColumnKey.VAT_RATE);
    /**
     * Ед. изм. и ставка НДС расширяются по содержимому не дальше двух своих долей по умолчанию — физически (доля книжного
     * листа в мм) и при выбранном кегле (доля × кегль / 10): «комплект», «упаковка» — в одну строку, а свободный текст
     * длиннее переносится по пробелам и таблицу не раздувает.
     */
    private static final double FLEX_MAX_SHARES = 2;
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
     * Ширины колонок и кегль таблицы. Классы колонок — по ключу (classify): деньги, количество, № и короткие ед. изм. /
     * ставка НДС не переносятся, их «нужда» — самое длинное содержимое (и не меньше одной буквы) + поля; ед. изм. или
     * ставка со строкой шире двух своих долей переносится по пробелам, её нужда — самое длинное слово (не шире двух долей);
     * текст переносится всегда. Тесная таблица уступает по порядку — первый подошедший шаг:
     * <ol>
     * <li>кегль от 10 до 8 pt шагом 0,5: деньги и короткие — большее из доли по умолчанию × кегль / 10 и нужды, текст —
     * доля по умолчанию × кегль / 10, наименованию — остаток, не меньше четверти. Теснота — в миллиметрах (доли по
     * умолчанию — книжного листа), раскладка — в долях листа; на альбомном листе, где теснота позволяет кегль, а доли
     * вместе больше 75 %, первыми уступают деньги и короткие (излишек сверх нужды, ровно сколько нужно), затем текст;</li>
     * <li>8 pt: деньги и короткие — ровно по нужде, текст сжимается пропорционально, не уже полей и четырёх букв,
     * наименованию — не меньше четверти;</li>
     * <li>то же, наименованию — не меньше пятой части;</li>
     * <li>только абсурдно тесная таблица: прежнее пропорциональное сжатие всех колонок (числа шире своих ячеек —
     * редактор предложит альбомный лист).</li>
     * </ol>
     * Обычные КП не меняются: 10 pt и доли по умолчанию держат цену (12 %) до 999 999,99 и сумму (13 %) до 9 999 999,99.
     */
    private static TableFit fitTable(List<KpDocument.Column> columns, List<KpDocument.Row> rows, boolean landscape) {
        double page = landscape ? TEXT_WIDTH_LANDSCAPE_MM : TEXT_WIDTH_PORTRAIT_MM;
        int n = columns.size();
        List<OfferColumnKey> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        double[] lineEm = new double[n];   // самая длинная строка ячейки колонки, em
        double[] wordEm = new double[n];   // самое длинное слово (переносы — только по обычному пробелу), em
        for (int i = 0; i < n; i++) {
            keys.add(OfferColumnKey.parse(columns.get(i).key()));
            labels.add(columns.get(i).label());
            for (KpDocument.Row r : rows) {
                if (i >= r.cells().size()) continue;   // объединённая ячейка раздела / «включено» — не своя
                for (String line : r.cells().get(i)) {
                    lineEm[i] = Math.max(lineEm[i], textEm(line));
                    for (String word : line.split("\\s+")) wordEm[i] = Math.max(wordEm[i], textEm(word));
                }
            }
        }
        // 1. кегль 10 → 8 pt, теснота — в миллиметрах
        for (double pt = TABLE_FONT_PT; pt >= MIN_TABLE_FONT_PT; pt -= TABLE_FONT_STEP_PT) {
            Classes c = classify(keys, lineEm, wordEm, pt, page);
            if (sum(c.fit(), targets(keys, c, pt, TEXT_WIDTH_PORTRAIT_MM / page)) > OTHERS_MAX_PERCENT + EPS) continue;
            double[] targets = targets(keys, c, pt, 1);
            double[] floors = textFloors(c.fit(), targets, pt, page);
            double[] fitted = relieve(c.fit(), targets, c.needs(), floors, 100 - NAME_MIN_PERCENT);
            if (fitted != null) {
                return new TableFit(columnList(keys, labels, c.nowrap(), percents(c.fit(), fitted, c.needs(), floors, NAME_MIN_PERCENT)), pt);
            }
        }
        // 2, 3. 8 pt: деньги и короткие — по нужде, текст сжимается; наименованию — четверть, затем пятая часть
        double pt = MIN_TABLE_FONT_PT;
        Classes c = classify(keys, lineEm, wordEm, pt, page);
        double[] targets = targets(keys, c, pt, 1);
        double[] floors = textFloors(c.fit(), targets, pt, page);
        for (double nameMin : new double[] {NAME_MIN_PERCENT, NAME_MIN_SQUEEZED_PERCENT}) {
            double[] fitted = squeezeText(c.fit(), targets, c.needs(), floors, 100 - nameMin);
            if (fitted != null) return new TableFit(columnList(keys, labels, c.nowrap(), percents(c.fit(), fitted, c.needs(), floors, nameMin)), pt);
        }
        // 4. абсурдно тесная таблица — прежнее пропорциональное сжатие (числа могут выйти за свои ячейки)
        return new TableFit(sized(keys, labels, c.nowrap(), targets), pt);
    }

    /** Классы колонок при кегле, нужды денег и коротких (% ширины набора, целые — округление их не урежет), неразрывность. */
    private record Classes(Fit[] fit, double[] needs, boolean[] nowrap) {}

    /**
     * Классы при кегле pt. Деньги и количество (MONEY), № (SHORT) — неразрывны, нужда — самая длинная строка + поля. Ед.
     * изм. и ставка НДС (SHORT) — так же, пока эта нужда не шире двух своих долей по умолчанию (FLEX_MAX_SHARES; в мм
     * книжного листа, доля × кегль / 10); шире — переносятся по пробелам, нужда — самое длинное слово, но не шире двух
     * долей (слово длиннее рвётся внутри ячейки). Остальное — текст (TEXT).
     */
    private static Classes classify(List<OfferColumnKey> keys, double[] lineEm, double[] wordEm, double pt, double page) {
        int n = keys.size();
        Fit[] fit = new Fit[n];
        double[] needs = new double[n];
        boolean[] nowrap = new boolean[n];
        for (int i = 0; i < n; i++) {
            OfferColumnKey key = keys.get(i);
            fit[i] = key == OfferColumnKey.NAME ? Fit.NAME
                    : key.align() == ColumnAlign.RIGHT || key == OfferColumnKey.QTY ? Fit.MONEY
                    : key == OfferColumnKey.NUM || FLEX_COLUMNS.contains(key) ? Fit.SHORT : Fit.TEXT;
            if (!content(fit[i])) continue;
            double lineMm = needMm(lineEm[i], pt);
            double maxMm = FLEX_MAX_SHARES * key.weight() / 100 * TEXT_WIDTH_PORTRAIT_MM * pt / TABLE_FONT_PT;
            boolean wraps = FLEX_COLUMNS.contains(key) && lineMm > maxMm + EPS;
            double mm = wraps ? Math.min(needMm(wordEm[i], pt), maxMm) : lineMm;
            needs[i] = Math.ceil(mm * 100 / page - EPS);
            nowrap[i] = !wraps;
        }
        return new Classes(fit, needs, nowrap);
    }

    /** Ширина ячейки под строку шириной em при кегле pt, мм: строка (не уже одной буквы) и поля. */
    private static double needMm(double em, double pt) {
        return Math.max(em, ONE_GLYPH_EM) * pt * MM_PER_PT + CELL_PADDING_MM;
    }

    /**
     * Веса шага 1 при кегле pt, % ширины набора: доля по умолчанию × кегль / 10 × scale (scale — доля книжного листа в
     * текущем: проверка тесноты в миллиметрах; 1 — доли раскладки), у денег и коротких — не меньше нужды.
     */
    private static double[] targets(List<OfferColumnKey> keys, Classes c, double pt, double scale) {
        double[] targets = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            if (c.fit()[i] == Fit.NAME) continue;
            targets[i] = keys.get(i).weight() * pt / TABLE_FONT_PT * scale;
            if (content(c.fit()[i])) targets[i] = Math.max(targets[i], c.needs()[i]);
        }
        return targets;
    }

    /** Нижний предел текстовой колонки: поля и четыре буквы (но не больше её доли — сжатие не расширяет). */
    private static double[] textFloors(Fit[] fit, double[] targets, double pt, double page) {
        double[] floors = new double[fit.length];
        double glyphs = Math.ceil((TEXT_MIN_EM * pt * MM_PER_PT + TEXT_PADDING_MM) * 100 / page - EPS);
        for (int i = 0; i < fit.length; i++) if (fit[i] == Fit.TEXT) floors[i] = Math.min(targets[i], glyphs);
        return floors;
    }

    /**
     * Шаг 1 на альбомном листе: теснота в миллиметрах позволяет кегль, а доли по умолчанию (доли листа) вместе больше 75 % —
     * первыми уступают деньги и короткие: излишек своей доли сверх нужды, пропорционально излишку и ровно сколько нужно (не
     * «до нужды»); не хватило — текст, пропорционально, не уже своего предела. На книжном листе доли и миллиметры совпадают —
     * сюда доходит только то, что влезает. null — не влезает и так (пробуется кегль меньше).
     */
    private static double[] relieve(Fit[] fit, double[] targets, double[] needs, double[] floors, double budget) {
        double over = sum(fit, targets) - budget;
        if (over <= EPS) return targets;
        double spare = 0;
        for (int i = 0; i < fit.length; i++) if (content(fit[i])) spare += targets[i] - needs[i];
        double fromSpare = Math.min(over, spare);
        double[] relieved = targets.clone();
        for (int i = 0; i < fit.length; i++) {
            if (content(fit[i]) && spare > EPS) relieved[i] = targets[i] - (targets[i] - needs[i]) * fromSpare / spare;
        }
        double fromText = over - fromSpare;
        if (fromText <= EPS) return relieved;
        double text = sum(fit, Fit.TEXT, targets);
        if (text - fromText + EPS < sum(fit, Fit.TEXT, floors)) return null;
        double[] shrunk = shrink(fit, Fit.TEXT, targets, floors, text - fromText);
        for (int i = 0; i < fit.length; i++) if (fit[i] == Fit.TEXT) relieved[i] = shrunk[i];
        return relieved;
    }

    /** Шаги 2–3: деньги и короткие — ровно по нужде, текст — пропорционально, не уже своего предела. null — не влезает. */
    private static double[] squeezeText(Fit[] fit, double[] targets, double[] needs, double[] floors, double budget) {
        double[] squeezed = new double[fit.length];
        double rest = budget;
        for (int i = 0; i < fit.length; i++) {
            if (content(fit[i])) {
                squeezed[i] = needs[i];
                rest -= needs[i];
            }
        }
        if (rest + EPS < sum(fit, Fit.TEXT, floors)) return null;
        double[] text = shrink(fit, Fit.TEXT, targets, floors, Math.min(rest, sum(fit, Fit.TEXT, targets)));
        for (int i = 0; i < fit.length; i++) if (fit[i] == Fit.TEXT) squeezed[i] = text[i];
        return squeezed;
    }

    /**
     * Колонки класса kind — в сумму total: пропорционально весам, но не ниже своего предела («водяное заполнение»:
     * упёршаяся в предел колонка остаётся на нём, остальные делят остаток заново).
     */
    private static double[] shrink(Fit[] fit, Fit kind, double[] weights, double[] floors, double total) {
        double[] shrunk = new double[fit.length];
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < fit.length; i++) if (fit[i] == kind) free.add(i);
        double rest = total;
        while (!free.isEmpty()) {
            double sum = 0;
            for (int i : free) sum += weights[i];
            double share = sum > 0 ? Math.min(1, rest / sum) : 1;
            List<Integer> stuck = new ArrayList<>();
            for (int i : free) if (weights[i] * share < floors[i] - EPS) stuck.add(i);
            if (stuck.isEmpty()) {
                for (int i : free) shrunk[i] = weights[i] * share;
                break;
            }
            for (int i : stuck) {
                shrunk[i] = floors[i];
                rest -= floors[i];
            }
            free.removeAll(stuck);
        }
        return shrunk;
    }

    private static double sum(Fit[] fit, double[] weights) {
        double sum = 0;
        for (int i = 0; i < fit.length; i++) if (fit[i] != Fit.NAME) sum += weights[i];
        return sum;
    }

    private static double sum(Fit[] fit, Fit kind, double[] weights) {
        double sum = 0;
        for (int i = 0; i < fit.length; i++) if (fit[i] == kind) sum += weights[i];
        return sum;
    }

    /** Деньги и короткие: ширина по содержимому, без переноса. */
    private static boolean content(Fit fit) {
        return fit == Fit.MONEY || fit == Fit.SHORT;
    }

    /**
     * Целые проценты шагов 1–3, сумма 100. Деньгам и коротким — их доля, округлённая вверх (нужда — целая, ниже неё
     * округление их не уводит), остальным — наибольшие остатки от оставшегося. Перебор снимается с тех, кто выше своего
     * предела (текст — поля и четыре буквы, наименование — его минимум), в последнюю очередь — с денег и коротких сверх
     * нужды.
     */
    private static int[] percents(Fit[] fit, double[] weights, double[] needs, double[] floors, double nameMin) {
        int n = fit.length;
        double[] exact = new double[n];
        int[] percent = new int[n], lower = new int[n];
        double others = 0;
        int name = -1;
        for (int i = 0; i < n; i++) {
            if (fit[i] == Fit.NAME) {
                name = i;
                continue;
            }
            exact[i] = weights[i];
            others += weights[i];
        }
        exact[name] = 100 - others;
        int sum = 0;
        for (int i = 0; i < n; i++) {
            lower[i] = i == name ? (int) Math.ceil(nameMin - EPS)
                    : content(fit[i]) ? (int) needs[i] : (int) Math.floor(floors[i] + EPS);
            percent[i] = Math.max(lower[i], content(fit[i]) ? (int) Math.ceil(exact[i] - EPS) : (int) Math.floor(exact[i] + EPS));
            sum += percent[i];
        }
        while (sum < 100) {   // недостача — тем, у кого остаток больше
            int best = -1;
            for (int i = 0; i < n; i++) if (best < 0 || exact[i] - percent[i] > exact[best] - percent[best]) best = i;
            percent[best]++;
            sum++;
        }
        while (sum > 100) {   // перебор — у тех, кто выше предела: сперва текст и наименование, затем деньги и короткие
            int best = -1;
            for (int pass = 0; pass < 2 && best < 0; pass++) {
                for (int i = 0; i < n; i++) {
                    if (percent[i] <= lower[i] || content(fit[i]) != (pass == 1)) continue;
                    if (best < 0 || exact[i] - percent[i] < exact[best] - percent[best]) best = i;
                }
            }
            if (best < 0) break;   // все на пределе — не случается: пределы шагов 1–3 вместе не больше 100
            percent[best]--;
            sum--;
        }
        return percent;
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
        return columnList(keys, labels, nowrap, percent);
    }

    private static List<KpDocument.Column> columnList(List<OfferColumnKey> keys, List<String> labels, boolean[] nowrap, int[] percent) {
        List<KpDocument.Column> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
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
