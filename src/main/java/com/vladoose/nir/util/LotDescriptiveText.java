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

    /**
     * Коммерческий хвост ТЗ СК-Фармации: ниже требований таблица продолжается условиями поставки по
     * ИНКОТЕРМС, «DDP пункт назначения», сроком поставки и местом дислокации — и вместе с ними несёт
     * ИМЯ И АДРЕС ЗАКАЗЧИКА («ГКП на ПХВ «Казталовская районная больница» … Адрес: ЗКО …»). Заказчик
     * раскрывает тендер прямее кода лота: зная больницу и предмет закупки, поставщик находит
     * объявление сам, а это ровно то, что анти-лик §9 из письма убирал.
     * <p>Якоря сняты с 39 живых разобранных ТЗ (позиции — в санитизированном тексте, том самом, что
     * видит обрезка письма):
     * <ul>
     *   <li>«Условия осуществления поставки» — 39/39 и во ВСЕХ 39 самый ранний из якорей (заголовок
     *       строки 4 таблицы), минимум 2408;</li>
     *   <li>«ИНКОТЕРМС» — 39/39 (в одном ТЗ стоит ПОСЛЕ «DDP пункт назначения»: порядок слов в шапке
     *       строки плавает — потому якорей несколько, как и рубежей у шапки);</li>
     *   <li>«DDP пункт» / «пункт(а) назначения» — 39/39; «место … дислокации» — 36/39;
     *       «Адрес: <Заглавная>» — 25/39 (два последних — подстраховка, не основной якорь).</li>
     * </ul>
     * Голый «DDP» якорем НЕ годится — встречается внутри требований («3 Гентри DDP Каталожный номер
     * DSP-PANEL-G3-UA»); «заказчик» тоже («выбор заказчиком одного из вариантов»); «Адрес» закрыт
     * lookbehind'ом от «IP-адрес:»/«MAC-адрес:» и требованием кириллической заглавной после двоеточия.
     */
    private static final Pattern SK_COMMERCIAL_TAIL = Pattern.compile(
            "Услови[яй]\\s+осуществления\\s+поставки"
                    + "|ИНКОТЕРМС"
                    + "|DDP\\s+пункт"
                    + "|пункт[а-я]*\\s+назначения"
                    + "|мест[а-я]*\\s+дислокации"
                    + "|(?<![\\p{IsAlphabetic}-])Адрес\\s*:\\s*(?-i:[А-ЯЁ])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Хвост среза начинается с номера строки таблицы («… 50/60 Гц 4 Условия осуществления поставки»). */
    private static final Pattern TRAILING_ROW_NUMBER = Pattern.compile("\\s+\\d{1,2}[.)]?\\s*$");
    /** Окно, в котором ищем заголовок документа — «Техническая спецификация» это 24 символа. */
    private static final int SK_TITLE_PROBE = 64;

    private LotDescriptiveText() {}

    /**
     * Санитизированный текст ТЗ для тела письма поставщику: тех. характеристики без закупочного
     * канцелярита (номера закупки/лота, наименование закупки, места/сроки поставки, количество) —
     * чтобы КП не раскрывало конкретный тендер. Та же сегментация, что у {@link #forMatching}, без
     * имени/бренда. goszakup режется по меткам, СК-Фармация — по шапке, и в любом случае из результата
     * вычищается код лота площадки ({@link #scrubPlatformCodes}).
     * <p>Сверх того — и только здесь, не в {@link #forMatching} — у ТЗ СК-Фармации срезается
     * коммерческий хвост с именем и адресом заказчика ({@link #SK_COMMERCIAL_TAIL}). Матчингу
     * комплектности полный текст нужен и вреда там не делает, а в письме заказчик — раскрытие
     * тендера. До этого среза хвост не утекал лишь потому, что таблица требований длиннее лимита
     * обрезки (запас 2459 против 1200 у {@code KpEmailComposer.SPEC_LIMIT}), то есть по свойству
     * ДАННЫХ, а не кода: лот с тремя критериями вместо пятнадцати отправил бы имя больницы поставщику.
     * Срез гарантию делает структурной — она держится при любом значении лимита.
     */
    public static String requirementsForEmail(String requiredSpec) {
        String sanitized = forMatching(null, null, requiredSpec);
        return isSkSpec(requiredSpec) ? cutCommercialTail(sanitized) : sanitized;
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
     * ТЗ СК-Фармации узнаётся по заголовку документа, а не по ветке сегментации: 5 из 39 живых SK-ТЗ
     * несут где-то в глубине goszakup-метку «Количество:» и уходят в ветку меток, а «Техническая
     * спецификация» в начале документа есть у 39/39 SK и у 0/1449 не-SK. Гейт по заголовку поэтому
     * покрывает обе ветки и не меняет goszakup-путь ни на байт.
     */
    private static boolean isSkSpec(String requiredSpec) {
        if (requiredSpec == null || requiredSpec.isBlank()) return false;
        String head = requiredSpec.strip();
        if (head.length() > SK_TITLE_PROBE) head = head.substring(0, SK_TITLE_PROBE);
        return SK_TITLE.matcher(head.replaceAll("\\s+", " ")).lookingAt();
    }

    /**
     * Срезает всё от первого коммерческого якоря до конца — вместе с ним уходят имя и адрес
     * заказчика. Требований срез не ест: на всех 39 живых ТЗ самый ранний якорь — это заголовок
     * строки 4 таблицы, а ниже идут только строки 4–7 (поставка, гарантийное обслуживание,
     * сопутствующие услуги) одинаковым бойлерплейтом — плюс в одном ТЗ казахский дубль русской
     * секции, тоже не уникальные требования.
     * <p>⚠ Именно поэтому срез сидит под гейтом {@link #isSkSpec}: в goszakup-ТЗ порядок обратный —
     * характеристики идут ПОСЛЕ условий поставки (живой лот 2865: «Условия поставки (в соответствии
     * с ИНКОТЕРМС 2010)» на 404-м символе, а «Тип детектора / Габаритные размеры / Вес» — после
     * него), и слепой срез по тем же якорям съел бы там реальные требования.
     * <p>Якорь в самом начале (требований нет вовсе) даёт пустую строку — письмо просто не рисует
     * строку «Требования:». Это безопасное направление отказа: лучше без требований, чем с заказчиком.
     */
    private static String cutCommercialTail(String s) {
        Matcher m = SK_COMMERCIAL_TAIL.matcher(s);
        if (!m.find()) return s;
        String head = s.substring(0, m.start());
        return TRAILING_ROW_NUMBER.matcher(head).replaceAll("").trim();
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
