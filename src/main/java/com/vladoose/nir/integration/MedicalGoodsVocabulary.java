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
            "стоматологическ", "дентальн", "хирургическ", "стол операционн", "светильник операционн", "перчат",
            "шприц", "катетер", "канюл", "зонд", "бинт", "пластыр", "электрод", "реагент", "тест-систем",
            "тест-полоск", "экспресс-тест", "пробирк", "контейнер биологическ", "контейнер сбор",
            "издели медицинск", "медицинского назначения", "расходн медицинск", "имплант", "протез",
            "материал шовн", "игл", "скальпел", "насос инфузионн", "насос шприцев", "инфузомат", "гемодиализ",
            "диализатор");

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

    /** Скомпилированные паттерны терма (на каждый стем, либо один на фразу); ≈ 150 термов, корпус — тысячи лотов. */
    private static final Map<String, Pattern[]> COMPILED = new ConcurrentHashMap<>();

    private MedicalGoodsVocabulary() {}

    public static boolean hasDeviceStrong(String text) { return matchesAny(text, DEVICE_STRONG); }
    public static boolean hasDeviceWeak(String text)   { return matchesAny(text, DEVICE_WEAK); }
    public static boolean hasServiceStrong(String text) { return matchesAny(text, SERVICE_STRONG); }
    public static boolean hasServiceWeak(String text)  { return matchesAny(text, SERVICE_WEAK); }
    public static boolean hasMedicine(String text)     { return text != null && MEDICINE.matcher(text).find(); }

    public static boolean matchesAny(String text, List<String> terms) {
        if (text == null || text.isBlank()) return false;
        String t = normalize(text);
        for (String term : terms) {
            if (matches(t, COMPILED.computeIfAbsent(term, MedicalGoodsVocabulary::compile))) return true;
        }
        return false;
    }

    private static boolean matches(String normalized, Pattern[] patterns) {
        for (Pattern p : patterns) {
            if (!p.matcher(normalized).find()) return false;
        }
        return true;
    }

    private static Pattern[] compile(String term) {
        String[] stems = normalize(term).trim().split("\\s+");
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
