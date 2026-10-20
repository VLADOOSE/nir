package com.vladoose.nir.service.productwatch.fit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Единица требования ТЗ или характеристики товара (спека 2026-10-20-product-spec-fit §5.2): семейство — что с чем
 * сравнимо, множитель — к базовой единице семейства. Текст — уже нормализованный ({@code ProductText.normalize}).
 */
public enum SpecUnit {
    W("мощность", 1, "Вт"), KW("мощность", 1000, "кВт"), VA("полная мощность", 1, "ВА"),
    HZ("частота", 1, "Гц"), KHZ("частота", 1e3, "кГц"), MHZ("частота", 1e6, "МГц"),
    MM("длина", 1, "мм"), CM("длина", 10, "см"), M("длина", 1000, "м"),
    G("масса", 1, "г"), KG("масса", 1000, "кг"),
    ML("объём", 1, "мл"), L("объём", 1000, "л"),
    L_MIN("поток", 1, "л/мин"), M3_H("поток воздуха", 1, "м³/ч"),
    KPA("давление", 1, "кПа"), MMHG("давление", 0.1333224, "мм рт. ст."),
    V("напряжение", 1, "В"), UV("малое напряжение", 1, "мкВ"), MV("малое напряжение", 1000, "мВ"),
    MA("ток", 1, "мА"), A("ток", 1000, "А"),
    OHM("сопротивление", 1, "Ом"), KOHM("сопротивление", 1e3, "кОм"), MOHM("сопротивление", 1e6, "МОм"),
    S("время", 1, "с"), MIN("время", 60, "мин"), H("время", 3600, "ч"),
    YEAR("срок", 12, "лет"), MONTH("срок", 1, "мес"),
    PCT("доля", 1, "%"), DB("звук", 1, "дБ"), M2("площадь", 1, "м²"), CM2("площадь", 1e-4, "см²"), MM2("площадь", 1e-6, "мм²"),
    W_M2("облучённость", 1, "Вт/м²"),
    CEL("температура", 1, "°C"), CHANNEL("каналы", 1, "кан."), PCS("штуки", 1, "шт");

    private final String family;
    private final double factor;
    private final String label;

    SpecUnit(String family, double factor, String label) {
        this.family = family; this.factor = factor; this.label = label;
    }

    public String family() { return family; }
    public double toBase(double v) { return v * factor; }
    public double fromBase(double v) { return v / factor; }
    public String label() { return label; }

    /** Единица в позиции from: её конец (позиция за ней). */
    public record Hit(SpecUnit unit, int end) {}

    /** Написания (нормализованный текст), длинные — раньше: «л/мин» раньше «л», «мм рт. ст.» раньше «мм». */
    private static final List<Map.Entry<String, SpecUnit>> FORMS;
    /** Написания, совпадающие с предлогом («5 в комплекте», «7 с защитой»): единица, только если за ними не слово. */
    private static final Set<String> WORDLIKE = Set.of("в", "с");
    static {
        List<Map.Entry<String, SpecUnit>> f = new ArrayList<>(List.of(
                Map.entry("л/мин", L_MIN), Map.entry("л / мин", L_MIN), Map.entry("литров в минуту", L_MIN),
                Map.entry("м3/ч", M3_H), Map.entry("м³/ч", M3_H), Map.entry("м3/час", M3_H), Map.entry("куб.м/час", M3_H),
                Map.entry("куб. м/час", M3_H), Map.entry("куб.м/ч", M3_H), Map.entry("куб. м/ч", M3_H),
                Map.entry("мм рт. ст.", MMHG), Map.entry("мм рт.ст.", MMHG), Map.entry("мм рт ст", MMHG), Map.entry("мм.рт.ст.", MMHG),
                Map.entry("вт/м2", W_M2), Map.entry("вт/м²", W_M2), Map.entry("вт/кв.м", W_M2),
                Map.entry("кв.м", M2), Map.entry("кв. м", M2), Map.entry("м2", M2), Map.entry("м²", M2),
                Map.entry("см²", CM2), Map.entry("см2", CM2), Map.entry("кв.см", CM2), Map.entry("кв. см", CM2),
                Map.entry("мм²", MM2), Map.entry("мм2", MM2), Map.entry("кв.мм", MM2), Map.entry("кв. мм", MM2),
                Map.entry("миллиметров", MM), Map.entry("сантиметров", CM), Map.entry("метров", M), Map.entry("метра", M),
                Map.entry("метр", M), Map.entry("литров", L), Map.entry("литра", L), Map.entry("литр", L),
                Map.entry("килограммов", KG), Map.entry("килограмма", KG), Map.entry("килограмм", KG),
                Map.entry("ватт", W), Map.entry("вольт", V), Map.entry("герц", HZ), Map.entry("секунды", S),
                Map.entry("минуты", MIN),
                Map.entry("кпа", KPA), Map.entry("квт", KW), Map.entry("вт", W), Map.entry("ва", VA),
                Map.entry("кгц", KHZ), Map.entry("мгц", MHZ), Map.entry("гц", HZ),
                Map.entry("мкв", UV), Map.entry("мв", MV), Map.entry("в", V),
                Map.entry("мом", MOHM), Map.entry("ком", KOHM), Map.entry("ом", OHM),
                Map.entry("ма", MA), Map.entry("ампер", A),
                Map.entry("мм", MM), Map.entry("см", CM), Map.entry("мл", ML), Map.entry("кг", KG), Map.entry("грамм", G),
                Map.entry("гр", G), Map.entry("дб", DB), Map.entry("%", PCT), Map.entry("°c", CEL), Map.entry("°с", CEL),
                Map.entry("градусов", CEL), Map.entry("град", CEL),
                Map.entry("часов", H), Map.entry("часа", H), Map.entry("час", H), Map.entry("ч", H),
                Map.entry("минут", MIN), Map.entry("мин", MIN), Map.entry("секунд", S), Map.entry("сек", S), Map.entry("с", S),
                Map.entry("месяцев", MONTH), Map.entry("месяца", MONTH), Map.entry("мес", MONTH),
                Map.entry("лет", YEAR), Map.entry("года", YEAR), Map.entry("год", YEAR),
                Map.entry("-канальный", CHANNEL), Map.entry("канальный", CHANNEL), Map.entry("каналов", CHANNEL),
                Map.entry("канала", CHANNEL), Map.entry("канал", CHANNEL),
                Map.entry("штук", PCS), Map.entry("шт", PCS), Map.entry("л", L), Map.entry("м", M)));
        f.sort(Comparator.comparingInt((Map.Entry<String, SpecUnit> e) -> -e.getKey().length()));
        FORMS = List.copyOf(f);
    }

    /** Единица, начинающаяся в from (пробелы пропускаются); за ней — не буква и не цифра. null — единицы нет. */
    public static Hit parseAt(String t, int from) {
        int i = skipSpaces(t, from);
        for (Map.Entry<String, SpecUnit> e : FORMS) {
            String form = e.getKey();
            if (!t.startsWith(form, i)) continue;
            int end = i + form.length();
            if (end < t.length() && wordChar(t.charAt(end)) && !form.endsWith(".")) continue;
            // «мм/с», «мм/мв»: составная единица, которой нет в списке, — не «мм»
            if (end + 1 < t.length() && t.charAt(end) == '/' && Character.isLetter(t.charAt(end + 1))) continue;
            if (WORDLIKE.contains(form)) {
                int next = skipSpaces(t, end);
                if (next < t.length() && Character.isLetter(t.charAt(next))) continue;
            }
            return new Hit(e.getValue(), end);
        }
        return null;
    }

    /** Продолжение слова: буква, цифра, надстрочная цифра («м³» — не «м»). */
    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || Character.getType(c) == Character.OTHER_NUMBER;
    }

    private static int skipSpaces(String t, int from) {
        int i = from;
        while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == ' ')) i++;
        return i;
    }
}
