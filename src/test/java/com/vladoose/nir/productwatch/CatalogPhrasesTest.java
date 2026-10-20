package com.vladoose.nir.productwatch;

import com.vladoose.nir.service.productwatch.CatalogPhrases;
import com.vladoose.nir.service.productwatch.CatalogProduct;
import com.vladoose.nir.service.productwatch.ProductText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило «ТЗ списано» (спека §5.4, калибровка задачи 6) на построенных примерах: каждая защита меняет исход сама.
 * На наборе настоящих ТЗ общие фразы и слова группы сейчас не несущие (решают окно описаний и доля ТЗ), поэтому их
 * действие закреплено здесь.
 */
class CatalogPhrasesTest {

    static final CatalogPhrases.Rules R = CatalogPhrases.Rules.DEFAULT;

    private static List<String> spec(String text) {
        return ProductText.words(ProductText.normalize(text)).stream().map(ProductText::prefix).toList();
    }

    private static CatalogPhrases.Copy copied(List<CatalogProduct> products, String specText, Set<String> own) {
        Map<Long, List<CatalogPhrases.Phrase>> phrases = CatalogPhrases.build(products, R);
        return CatalogPhrases.copied(products, phrases, spec(specText), List.of(), own, R);
    }

    @Test
    void genericPhrasesNeverCount() {
        List<CatalogProduct> p = List.of(
                new CatalogProduct(1, "Блок управления; Шнур питания; Руководство по эксплуатации", "Фотек", "a", null, null, null),
                new CatalogProduct(2, "Изделие прочее", "Фотек", "b", null, null, null));
        assertThat(copied(p, "Блок управления. Шнур питания. Руководство по эксплуатации.", Set.of())).isNull();
    }

    @Test
    void groupOwnWordsAloneDoNotCount() {
        List<CatalogProduct> p = List.of(
                new CatalogProduct(1, "Рециркулятор бактерицидный; Бактерицидный рециркулятор", "ЭМА", "a", null, null, null),
                new CatalogProduct(2, "Изделие прочее", "ЭМА", "b", null, null, null));
        String tz = "Рециркулятор бактерицидный палатный. Изделие: бактерицидный рециркулятор.";   // два разных места
        assertThat(copied(p, tz, Set.of())).as("без слов группы — «списано»").isNotNull();
        assertThat(copied(p, tz, Set.of("рецир", "бакте"))).as("слова самой группы — в любом ТЗ её вида").isNull();
    }

    @Test
    void samePlaceMatchedByVariantsCountsOnce() {
        // 60 прочих товаров: у трёх вариантов слова «держатель шланга напольный» — редкие для каталога (≤ 5 %)
        List<CatalogProduct> p = new java.util.ArrayList<>(java.util.stream.LongStream.rangeClosed(10, 69)
                .mapToObj(i -> new CatalogProduct(i, "Изделие прочее", "ЭМА", "x" + i, null, null, null)).toList());
        p.add(new CatalogProduct(1, "Держатель шланга напольный 2Х15", "ЭМА", "a", null, null, null));
        p.add(new CatalogProduct(2, "Держатель шланга напольный 3Х15", "ЭМА", "b", null, null, null));
        p.add(new CatalogProduct(3, "Держатель шланга напольный 4Х15", "ЭМА", "c", null, null, null));
        assertThat(copied(p, "Держатель шланга напольный.", Set.of())).as("одно место ТЗ — одна фраза").isNull();
        p.add(new CatalogProduct(4, "Наконечник аспирационный изогнутый", "ЭМА", "d", null, null, null));
        assertThat(copied(p, "Держатель шланга напольный. Наконечник аспирационный изогнутый.", Set.of()))
                .as("два разных места — «списано»").isNotNull();
    }

    @Test
    void longForeignSpecWithTwoSharedPlacesIsNotCopied() {
        List<CatalogProduct> p = List.of(
                new CatalogProduct(1, "Держатель шланга аспирационного; Наконечник аспирационный изогнутый", "Фотек", "a", null, null, null),
                new CatalogProduct(2, "Изделие прочее", "Фотек", "b", null, null, null));
        String filler = " Требования к поставке и гарантии согласно договору поставщика".repeat(20);
        assertThat(copied(p, "Держатель шланга аспирационного. Наконечник аспирационный изогнутый.", Set.of())).isNotNull();
        assertThat(copied(p, "Держатель шланга аспирационного. Наконечник аспирационный изогнутый." + filler, Set.of()))
                .as("два места, но меньше 4 % ТЗ").isNull();
    }
}
