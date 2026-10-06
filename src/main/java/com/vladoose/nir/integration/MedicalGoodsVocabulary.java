package com.vladoose.nir.integration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Общий словарь медизделий для фильтров релевантности goszakup и СК-Фармации. Только термины — решения
 * принимают фильтры площадок.
 *
 * <p>Терм — один или несколько стемов через пробел; совпадение — каждый стем встречается как НАЧАЛО слова
 * (в любом порядке): «концентратор кислород» ловит и «Кислородный концентратор», а прежняя подстрока
 * «кислородн концентратор» не совпадала никогда (ревью A2). Граница слова — любой не-буквенно-цифровой
 * символ, в том числе дефис («тест-полоски» ловится стемом «полоск»).
 *
 * <p>Стемы многостемного терма должны стоять РЯДОМ: между ними не больше {@link #WINDOW_WORDS} других слов
 * («Тележка | медицинская», «Концентратор кислорода 5 л»). Без окна стемы находились в любом месте описания
 * на 600 символов: «Тестер | для определения подлинности банкнот» совпадал с «тест определени», «Стекло оконное |
 * предметная поверхность» — с «предметн стекл» (ревью Task 3).
 *
 * <p>Терм со стемами через «_» («тест_полос», «предметн_стекл») — слова подряд и в этом порядке, через пробелы
 * или дефис, каждый стем — начало своего слова: «Тест полосы», «Тест-полоски», «на предметном стекле», но не
 * «Полоса стальная тестовая» и не «Стекло оконное | предметная поверхность». Общие слова
 * («тест … для определения») в термы не берутся: «Тестер | для определения подлинности банкнот».
 *
 * <p>Исключение: если в терме есть стем короче 3 символов («работы по»), терм ищется ЦЕЛИКОМ как фраза
 * (слова — через любые пробельные символы, в том числе перевод строки) — с началом слова в начале и концом
 * слова после короткого стема: «по» как начало слова есть почти в любом
 * тексте, и по-словный поиск превратил бы «работы по» в просто «работы».
 *
 * <p>Текст сравнивается в нижнем регистре, «ё» приравнивается к «е» («лёгких» ловится стемом «легк»).
 */
public final class MedicalGoodsVocabulary {

    /** Однозначные медизделия: перебивают маркеры лекарств и слабые маркеры услуг. */
    public static final List<String> DEVICE_STRONG = List.of(
            "узи", "ультразвук", "эхокардиограф", "рентген", "флюорограф", "маммограф", "ангиограф", "томограф",
            "мрт", "ивл", "вентиляц легк", "наркозн", "анестезиолог", "анализатор", "гематологическ анализатор",
            "коагулометр", "центрифуг", "микроскоп", "стерилизатор", "стерилизационн", "автоклав", "эндоскоп",
            "гастроскоп", "колоноскоп", "бронхоскоп", "лапароскоп", "цистоскоп", "ларингоскоп", "дефибрил",
            "кардиограф", "электрокардиограф", "экг", "спирометр", "инкубатор", "облучател", "рециркулятор",
            "бактерицидн", "физиотерап", "электрофорез", "магнитотерап", "отсасыват", "аспиратор", "оксиметр",
            "пульсоксиметр", "тонометр", "глюкометр", "коагулятор", "ингалятор", "небулайзер", "негатоскоп",
            "дозатор", "концентратор кислород", "весы медицин", "холодильник медицин", "термоконтейнер",
            "кровать функционал", "кровать медицин", "кушетк", "монитор пациент", "монитор прикроватн",
            "тележк медицин", "ножниц медицин", "лоток медицин", "лотк медицин", "кресл медицин", "бахил",
            "стоматолог", "дентальн", "хирурги", "стол операционн", "светильник операционн", "перчат",
            "шприц", "катетер", "канюл", "зонд", "бинт", "пластыр", "электрод", "реагент", "тест-систем",
            "тест-полоск", "экспресс тест", "пробирк", "контейнер биологическ", "контейнер сбор",
            "издели медицинск", "медицинского назначения", "расходн медицинск", "имплант", "протез",
            "материал шовн", "игл", "скальпел", "насос инфузионн", "насос шприцев", "инфузомат", "гемодиализ",
            "диализатор",
            // СК-Фармация (Task 3): расходники и лабораторное, которые прежний список не знал
            "тест_полос", "тест антиген", "тест антител", "тест-кассет", "иммунохроматограф", "иммуноферментн",
            "контейнер антикоагулянт", "контейнер коагулянт", "контейнер крови", "контейнер кровь", "контейнер биоматериал",
            "контейнер мочи", "контейнер мочев", "микропробирк", "удлинител инфузионн", "скобк пуповин", "сборник мочи",
            "простын стерильн", "простын перфорац", "пеленк впитыва", "биопроб", "гистологическ", "цитологическ",
            "загубник", "фиброэндоскоп", "видеоэндоскоп", "сепаратор клет", "зеркал влагалищн", "помп инсулинов",
            "быстрозамораживател", "предметн_стекл", "стекл_предметн");

    /**
     * Сильные термины-ПРИЛАГАТЕЛЬНЫЕ (назначение, а не предмет): «Стоматологический анестетик раствор для инъекций»
     * — лекарство. Такой термин проигрывает лекарственной форме {@link #DRUG_FORM}; термины-предметы (шприц, игла,
     * катетер) — нет: «Шприц … для инъекций» остаётся изделием. См. {@link #hasDeviceStrongOverDrugForm}.
     */
    public static final List<String> ADJECTIVE_STRONG = List.of(
            "стоматолог", "дентальн", "хирурги", "гистологическ", "цитологическ", "анестезиолог", "бактерицидн",
            "стерилизационн", "физиотерап");

    /** Лекарственная форма, которая бьёт только {@link #ADJECTIVE_STRONG}. */
    private static final Pattern DRUG_FORM = Pattern.compile("(?iU)"
            + "раствор\\s+для\\s+инъекц|раствор\\s+для\\s+инфуз|формалин|\\bмг\\b|мг/мл");

    private static final List<String> STRONG_OBJECTS = DEVICE_STRONG.stream()
            .filter(t -> !ADJECTIVE_STRONG.contains(t)).toList();

    /** Общие слова: изделие, только если нет лекарственного вето (СК-Фармация). goszakup их не использует. */
    public static final List<String> DEVICE_WEAK = List.of(
            "аппарат", "установк", "монитор", "издели", "инструмент", "светильник", "насос", "помп", "кровать",
            "кресл", "весы", "система", "набор", "комплект", "стол", "тележк", "штатив", "бахил", "маск", "халат",
            "салфетк", "шпател", "пинцет", "зажим", "ножниц");

    /**
     * Лот — услуга/работа/не-медицина независимо от терминов изделий. Ищется только в НАЗВАНИИ лота.
     * «сварочн» — электроды и прочее сварочное: стем «электрод» иначе тянул бы их в медизделия.
     */
    public static final List<String> SERVICE_STRONG = List.of(
            "услуг", "работы по", "работ по", "обучен", "утилизац", "отход", "ремонт", "обслуживан", "аренд",
            "страхован", "пошив", "стирк", "поверк", "метролог", "летательн", "беспилотн", "дрон", "сварочн");

    /** Маркер услуги, который проигрывает термину изделия в том же названии («Кушетка для осмотра»). */
    public static final List<String> SERVICE_WEAK = List.of("осмотр", "удален", "замер", "монтаж", "потолок");

    /**
     * Тара для сбора медицинских отходов (КБУ, контейнеры, коробки, пакеты) — товар, который West-Med поставляет
     * (решение 2026-10-07), хотя в тексте есть «отход»/«утилизац» из {@link #SERVICE_STRONG}. Предмет тары ищется
     * в НАЗВАНИИ лота, признак медотходов — в названии или описании (площадка делит «Контейнер» / «для сбора
     * медицинских отходов» как угодно).
     */
    public static final List<String> WASTE_CONTAINER = List.of("контейнер", "коробк", "пакет", "мешк", "мешок", "кбу");

    /** Признак медотходов для {@link #WASTE_CONTAINER}: «отход» + «медицин», либо КБУ (класс сам по себе медицинский). */
    public static final List<String> MEDICAL_WASTE = List.of("отход медицин", "кбу");

    /** Названия-услуги вокруг отходов: тара в них — не предмет закупки («Услуги по утилизации отходов в контейнерах»). */
    public static final List<String> WASTE_SERVICE = List.of("услуг", "вывоз", "работы по", "работ по");

    private static final Pattern MEDICINE = Pattern.compile("(?iU)"
            + "таблетк|ампул|капсул|мазь|сироп|инъекц|порошок|суспензи|инфузи|раствор для|флакон|драже|гранул"
            + "|свеч|суппозитор|аэрозол|настойк|вакцин|сыворотк|инсулин|антибиотик|\\bмг\\b|мг/мл|\\bме\\b|\\bмкг\\b");

    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";
    private static final int SHORT_STEM = 3;
    /** Многостемный терм: между стемами — не больше этого числа других слов (в любом порядке). */
    static final int WINDOW_WORDS = 3;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Скомпилированные паттерны терма (на каждый стем, либо один на фразу); ≈ 150 термов, корпус — тысячи лотов. */
    private static final Map<String, Pattern[]> COMPILED = new ConcurrentHashMap<>();

    private MedicalGoodsVocabulary() {}

    public static boolean hasDeviceStrong(String text) { return matchesAny(text, DEVICE_STRONG); }
    public static boolean hasDeviceWeak(String text)   { return matchesAny(text, DEVICE_WEAK); }
    public static boolean hasServiceStrong(String text) { return matchesAny(text, SERVICE_STRONG); }
    public static boolean hasServiceWeak(String text)  { return matchesAny(text, SERVICE_WEAK); }
    public static boolean hasMedicine(String text)     { return text != null && MEDICINE.matcher(text).find(); }

    /** Сильное изделие, где термин-прилагательное не засчитывается при лекарственной форме (СК-Фармация). */
    public static boolean hasDeviceStrongOverDrugForm(String text) {
        if (text != null && DRUG_FORM.matcher(text).find()) return matchesAny(text, STRONG_OBJECTS);
        return hasDeviceStrong(text);
    }

    /**
     * Тара для сбора медицинских отходов: предмет тары — в названии, без «услуг»/«вывоз»/«работы по» в названии,
     * признак медотходов — в названии или описании.
     */
    public static boolean isMedicalWasteContainer(String name, String descr) {
        String n = name == null ? "" : name;
        String all = (n + " " + (descr == null ? "" : descr)).trim();
        return matchesAny(n, WASTE_CONTAINER) && !matchesAny(n, WASTE_SERVICE) && matchesAny(all, MEDICAL_WASTE);
    }

    public static boolean matchesAny(String text, List<String> terms) {
        if (text == null || text.isBlank()) return false;
        String t = normalize(text);
        int[] wordStarts = null;
        for (String term : terms) {
            Pattern[] ps = COMPILED.computeIfAbsent(term, MedicalGoodsVocabulary::compile);
            if (ps.length == 1) {
                if (ps[0].matcher(t).find()) return true;
                continue;
            }
            if (wordStarts == null) wordStarts = wordStarts(t);
            if (matchesWithin(t, ps, wordStarts)) return true;
        }
        return false;
    }

    /** Есть ли набор совпадений всех стемов, укладывающийся в окно {@link #WINDOW_WORDS} слов. */
    private static boolean matchesWithin(String normalized, Pattern[] patterns, int[] wordStarts) {
        int[][] idx = new int[patterns.length][];
        for (int i = 0; i < patterns.length; i++) {
            List<Integer> found = new java.util.ArrayList<>();
            var m = patterns[i].matcher(normalized);
            while (m.find()) found.add(wordIndex(wordStarts, m.start()));
            if (found.isEmpty()) return false;
            idx[i] = found.stream().mapToInt(Integer::intValue).toArray();
        }
        // якорь — каждое совпадение первого стема; остальным стемам ищем совпадение в окне вокруг якоря
        for (int anchor : idx[0]) {
            for (int lo = anchor - WINDOW_WORDS - 1; lo <= anchor; lo++) {
                int hi = lo + WINDOW_WORDS + 1;
                boolean all = true;
                for (int i = 1; i < idx.length && all; i++) {
                    boolean any = false;
                    for (int w : idx[i]) if (w >= lo && w <= hi) { any = true; break; }
                    all = any;
                }
                if (all) return true;
            }
        }
        return false;
    }

    private static int[] wordStarts(String normalized) {
        var m = WORD.matcher(normalized);
        List<Integer> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.start());
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Номер слова, в котором (или на границе которого) начинается совпадение стема. */
    private static int wordIndex(int[] wordStarts, int pos) {
        int i = java.util.Arrays.binarySearch(wordStarts, pos);
        return i >= 0 ? i : Math.max(0, -i - 2);
    }

    private static Pattern[] compile(String term) {
        String norm = normalize(term).trim();
        if (norm.contains("_")) {   // «тест_полос»: слова подряд, через пробелы или дефис, в этом порядке
            String[] parts = norm.split("_");
            StringBuilder q = new StringBuilder(WORD_START);
            for (int i = 0; i < parts.length; i++) q.append(i == 0 ? "" : "[\\p{L}\\p{N}]*[\\s\\-]+").append(Pattern.quote(parts[i]));
            return new Pattern[] { Pattern.compile(q.toString(), Pattern.UNICODE_CHARACTER_CLASS) };
        }
        String[] stems = norm.split("\\s+");
        boolean hasShort = false;
        for (String s : stems) if (s.length() < SHORT_STEM) { hasShort = true; break; }
        if (hasShort) {
            String last = stems[stems.length - 1];
            StringBuilder q = new StringBuilder();
            for (int i = 0; i < stems.length; i++) q.append(i == 0 ? "" : "\\s+").append(Pattern.quote(stems[i]));
            String phrase = WORD_START + q
                    + (last.length() < SHORT_STEM ? WORD_END : "");
            return new Pattern[] { Pattern.compile(phrase, Pattern.UNICODE_CHARACTER_CLASS) };
        }
        Pattern[] out = new Pattern[stems.length];
        for (int i = 0; i < stems.length; i++) {
            out[i] = Pattern.compile(WORD_START + Pattern.quote(stems[i]), Pattern.UNICODE_CHARACTER_CLASS);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
