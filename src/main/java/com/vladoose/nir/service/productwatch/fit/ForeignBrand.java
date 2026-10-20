package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.service.productwatch.BrandModels;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «ТЗ под чужой бренд» (спека §5.5): поля «Производитель / Өндіруші / Модель / Страна …», слово латиницей с кодом модели
 * рядом или повторённое, бренд из производителей реестра НЦЭЛС — кроме наших брендов и совместимости («типа X»,
 * «разъём X», «датчик X», «на пульсоксиметр X»). Калибровка — набор ТЗ (§3 спеки): BOWA, ZOLL — да; NELLCOR, Mindray-датчик — нет.
 */
public final class ForeignBrand {

    private static final Pattern FIELD = Pattern.compile(
            "(?iu)(производитель|изготовитель|өндіруші|модель|марка|страна[\\s-]+(?:производитель|производства|происхождения|изготовления))"
                    + "\\s*[:\\-–—]\\s*([\\p{L}][\\p{L}\\p{N}\\-]{1,40})");
    private static final Pattern LATIN = Pattern.compile("(?<![\\p{L}\\p{N}])([A-Z][A-Za-z]{3,}|[A-Z]{3,})(?![\\p{L}\\p{N}])");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    /** Римское число («отведения I, II, III», «класс III») — не бренд. */
    private static final Pattern ROMAN = Pattern.compile("M{0,3}(C[MD]|D?C{0,3})(X[CL]|L?X{0,3})(I[XV]|V?I{0,3})");
    private static final Set<String> STOP = Set.of("spo", "tft", "lcd", "led", "usb", "ddp", "iso", "iec", "aha", "qrs", "ecg",
            "ekg", "lan", "wlan", "ethernet", "wifi", "bluetooth", "dicom", "pdf", "nibp", "ibp", "abs", "pvc", "dehp", "oem", "mdd",
            "din", "eec", "non", "stick", "gold", "standby", "shuko", "cut", "coag", "edge", "bte", "ips", "oled", "agcl", "retina",
            "air", "plus", "mini", "pro", "max", "lite", "type", "class", "mode", "auto", "dvd", "ram", "ssd", "hdd", "cpu", "gpu",
            "ups", "gsm", "gps", "rfid", "hdmi", "vga", "fda", "gmp", "aed", "cpr", "ivl", "uzi", "uhf", "vhf", "ntc", "ptc",
            "aureus", "officinalis", "latex", "nitrile", "luer", "lock", "luerlock", "json", "xml", "html", "word", "excel",
            // стандарты и организации по стандартам («протокол … AAMI DF-80», «IEEE 802.11») — не производители
            "aami", "ansi", "astm", "ieee", "nema", "cispr", "cenelec", "nist", "osha", "gost");
    private static final Set<String> GENERIC = Set.of("medical", "medic", "healthcare", "health", "systems", "system", "technologies",
            "technology", "tech", "electronics", "electronic", "international", "group", "company", "corporation", "industries",
            "industry", "instruments", "instrument", "products", "product", "solutions", "devices", "device", "scientific",
            "biomedical", "bio", "life", "sciences", "science", "care", "gmbh", "ltd", "llc", "inc", "corp", "limited", "holding",
            "holdings", "manufacturing", "trading", "equipment", "supplies", "china", "germany", "korea", "japan", "italy", "france",
            "india", "turkey", "usa", "shenzhen", "shanghai", "beijing", "jiangsu", "zhejiang", "guangdong", "hangzhou", "suzhou",
            "wuhan", "ningbo", "tianjin", "qingdao", "changzhou", "xiamen", "chongqing", "guangzhou", "foshan", "zhuhai", "nanjing",
            "medizintechnik", "medizin", "technik", "pharma", "diagnostics", "diagnostic", "laboratories", "laboratory");
    private static final Set<String> COMPAT = Set.of("типа", "тип", "разъем", "разъема", "разъемом", "разъём", "разъёма", "разъёмом",
            "совместим", "совместимы", "совместимый", "совместимые", "совместимость", "коннектор", "коннектором", "стандарта",
            "стандарт", "датчик", "датчиком", "датчики", "на", "к", "режим", "режима", "режимом");
    private static final Set<String> OUR_COUNTRIES = Set.of("россия", "рф", "российская", "казахстан", "рк", "республика");
    /** Наши бренды латиницей (транслитерации), сверх {@link BrandModels#GLOBAL_BRANDS}. */
    private static final List<String> OUR_LATIN = List.of("fotek", "axion", "aksion", "emaled", "mitar", "actiton", "onyx",
            "termoshov", "orion");

    private ForeignBrand() {}

    public static String detect(String text, Set<String> ours, Set<String> known) {
        if (text == null || text.isBlank()) return null;
        Matcher f = FIELD.matcher(text);
        while (f.find()) {
            String label = f.group(1).toLowerCase(Locale.ROOT), v = f.group(2);
            String key = v.toLowerCase(Locale.ROOT);
            if (label.startsWith("страна")) {
                if (!OUR_COUNTRIES.contains(key) && key.length() >= 4) return capitalize(v);
                continue;
            }
            if (ours.contains(key) || STOP.contains(key)) continue;
            if (latin(v) && v.length() >= 3) return v;
            if (known.contains(key)) return v;
        }
        List<String> words = new ArrayList<>();
        List<Integer> wordStarts = new ArrayList<>();
        Matcher w = WORD.matcher(text);
        while (w.find()) { words.add(w.group()); wordStarts.add(w.start()); }
        Map<String, List<Integer>> occ = new LinkedHashMap<>();   // токен (нижний регистр) → номера слов
        Map<String, String> asWritten = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            String tok = words.get(i);
            if (!LATIN.matcher(tok).matches()) continue;
            String key = tok.toLowerCase(Locale.ROOT);
            if (STOP.contains(key) || GENERIC.contains(key) || ours.contains(key) || ROMAN.matcher(tok).matches()) continue;
            occ.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            asWritten.putIfAbsent(key, tok);
        }
        for (Map.Entry<String, List<Integer>> e : occ.entrySet()) {
            List<Integer> at = e.getValue();
            if (at.stream().allMatch(i -> compat(words, i))) continue;
            boolean strong = known.contains(e.getKey()) || at.size() >= 2 || at.stream().anyMatch(i -> modelCodeAfter(words, i));
            if (strong) return asWritten.get(e.getKey());
        }
        return null;
    }

    /** Совместимость: одно из трёх слов перед токеном — «типа», «разъём», «датчик», «на» … */
    private static boolean compat(List<String> words, int i) {
        for (int k = Math.max(0, i - 3); k < i; k++) if (COMPAT.contains(words.get(k).toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Код модели: в двух следующих словах есть цифра («BOWA ARC 303»). */
    private static boolean modelCodeAfter(List<String> words, int i) {
        for (int k = i + 1; k <= Math.min(words.size() - 1, i + 2); k++) {
            if (words.get(k).chars().anyMatch(Character::isDigit)) return true;
        }
        return false;
    }

    public static Set<String> ours(Collection<String> productNames, Collection<String> siteBrands) {
        Set<String> out = new HashSet<>(OUR_LATIN);
        for (String b : BrandModels.GLOBAL_BRANDS) out.add(b.toLowerCase(Locale.ROOT));
        for (String b : siteBrands) {
            if (b == null) continue;
            for (String t : b.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (t.length() >= 3 && !t.equals("завод") && !t.equals("нпп")) out.add(t);
        }
        for (String name : productNames) {
            if (name == null) continue;
            List<String> words = new ArrayList<>();
            Matcher m = WORD.matcher(name);
            while (m.find()) words.add(m.group());
            for (int i = 0; i < words.size(); i++) {
                if (LATIN.matcher(words.get(i)).matches() && !compat(words, i)) out.add(words.get(i).toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    public static Set<String> registryBrands(Collection<String> producers) {
        Set<String> out = new HashSet<>();
        for (String p : producers) {
            if (p == null) continue;
            Matcher m = LATIN.matcher(p);
            while (m.find()) {
                String key = m.group(1).toLowerCase(Locale.ROOT);
                if (key.length() >= 4 && !GENERIC.contains(key) && !STOP.contains(key)) out.add(key);
            }
        }
        return Set.copyOf(out);
    }

    private static boolean latin(String s) {
        return s.chars().anyMatch(c -> c < 128 && Character.isLetter(c)) && s.chars().noneMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
