package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;

import java.util.ArrayList;
import java.util.List;

/**
 * Скомпилированная группа (спека §5.2). Слово поиска — стемом ({@link ProductText#searchStem}) в названии лота (в описании —
 * только у родового названия); у объявления с одним лотом — ещё в его названии. Уточнения — в тексте лота (+ название
 * объявления, если лот один);
 * исключения — только в тексте лота: общее название «Закуп медицинских изделий» не должно ни пропускать, ни резать
 * отдельные лоты многолотового объявления.
 */
public record GroupRule(long id, String name, boolean brandGroup, List<String> searchWords, List<String> stems,
                        List<String> require, List<String> exclude, List<BrandModels.Code> codes) {

    public static GroupRule of(ProductGroup g) {
        List<String> words = g.getSearchWords() == null ? List.of() : List.copyOf(g.getSearchWords());
        List<String> stems = words.stream().map(ProductText::searchStem).toList();
        List<BrandModels.Code> codes = new ArrayList<>();
        List<String> brandWords = g.isBrandGroup() ? words : (g.getBrandWords() == null ? List.of() : g.getBrandWords());
        for (String w : brandWords) codes.add(new BrandModels.Code(w, BrandModels.compile(w), null));
        return new GroupRule(g.getId(), g.getName(), g.isBrandGroup(), words, stems,
                norm(g.getRequireWords()), norm(g.getExcludeWords()), List.copyOf(codes));
    }

    /** Слово поиска, по которому текст прошёл группу, или null: весь текст — как название (пункт заявки, поиск по стране). */
    public String typeWord(String lotText, String announcement, boolean singleLot) {
        return typeWord(lotText, lotText, announcement, singleLot);
    }

    /**
     * Слово поиска, по которому лот прошёл группу, или null. Тексты — уже нормализованы; {@code lotText} — название и описание.
     * Слово ищется в НАЗВАНИИ лота, у объявления с одним лотом — ещё в названии объявления; в описании — только когда
     * название родовое ({@link ProductText#genericName}: ЕНС ТРУ «Изделия различные прочие…», «Аппарат»). Иначе описание
     * называет чужой предмет: часть набора («Комплект для операции на бедре» — «Карандаш электрохирургический»),
     * совместимость («Баллон кислородный» — «для аппарата искусственной вентиляции легких»), прилагательное («Халат» —
     * «стойкий к жидкостям»). Выгрузка прода 20.10: так набиралось 46 ложных «🏷 наш вид» из 93, настоящие не теряются.
     */
    public String typeWord(String lotName, String lotText, String announcement, boolean singleLot) {
        String hit = hit(lotName, lotText, announcement, singleLot);
        if (hit == null) return null;
        String scope = singleLot ? lotText + " | " + announcement : lotText;
        if (!require.isEmpty() && !MedicalGoodsVocabulary.matchesAny(scope, require)) return null;
        if (!exclude.isEmpty() && MedicalGoodsVocabulary.matchesAny(lotText, exclude)) return null;
        return hit;
    }

    /**
     * Почему лот не прошёл группу — для «Проверить» (спека §12.1); null — прошёл. Тексты — уже нормализованы.
     * Исключение называется раньше уточнения: «Электрод сварочный» — исключён, а не «нет уточнения».
     */
    public String rejectReason(String lotName, String lotText, String announcement, boolean singleLot) {
        if (typeWord(lotName, lotText, announcement, singleLot) != null) return null;
        if (hit(lotName, lotText, announcement, singleLot) == null) {
            return firstStem(lotText) != null ? "слово поиска только в описании — в названии лота другой предмет" : "нет слова поиска в лоте";
        }
        for (String e : exclude) if (MedicalGoodsVocabulary.matchesAny(lotText, List.of(e))) return "исключение «" + e + "»";
        return "нет уточнения (" + String.join(", ", require) + ")";
    }

    private String hit(String lotName, String lotText, String announcement, boolean singleLot) {
        String hit = firstStem(lotName);
        if (hit == null && singleLot) hit = firstStem(announcement);
        if (hit == null && ProductText.genericName(lotName)) hit = firstStem(lotText);
        return hit;
    }

    private String firstStem(String text) {
        if (text == null || text.isEmpty()) return null;
        for (int i = 0; i < stems.size(); i++) {
            if (MedicalGoodsVocabulary.matchesAny(text, List.of(stems.get(i)))) return searchWords.get(i);
        }
        return null;
    }

    private static List<String> norm(List<String> xs) {
        return xs == null ? List.of() : xs.stream().map(ProductText::normalize).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
