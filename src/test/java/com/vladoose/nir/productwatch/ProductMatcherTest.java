package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Сопоставление лота с нашей продукцией (спека §5): TYPE / NAMED / COPIED, похожие товары. */
class ProductMatcherTest {

    static CatalogSnapshot snap;
    static String astana;

    @BeforeAll
    static void load() {
        snap = CatalogSnapshot.build(ProductFixtures.groups(), ProductFixtures.catalog());
        astana = ProductFixtures.spec("17779323-1.pdf");
    }

    private static List<LotMatch> match(String announcement, LotInput... lots) {
        return ProductMatcher.match(snap, announcement, List.of(lots));
    }

    @Test
    void astana_copiedFromFotekAsd() {
        List<LotMatch> m = match("Аппарат для аспирации дыма",
                new LotInput(1, "Отсасыватель", "для аспирации жидкого содержимого", astana));
        LotMatch suction = m.stream().filter(x -> x.groupId() == 10).findFirst().orElseThrow();
        assertThat(suction.level()).isEqualTo(ProductMatchLevel.COPIED);
        // «блок аспирации» — слово группы и частое слово: общая фраза любого ТЗ отсасывателя (калибровка задачи 6)
        assertThat(suction.evidence()).contains("держатель шланга аспирационного напольный").contains("амбулаторной гинекологии");
        String product = snap.products().stream().filter(p -> p.id() == suction.productId()).findFirst().orElseThrow().name();
        assertThat(product).startsWith("Аппарат для аспирации дыма АСД");
        assertThat(suction.specUsed()).isTrue();
    }

    @Test
    void astanaWithoutSpec_typeOnly_withSimilar() {
        LotMatch m = match("Аппарат для аспирации дыма", new LotInput(1, "Отсасыватель", "для аспирации жидкого содержимого", null))
                .stream().filter(x -> x.groupId() == 10).findFirst().orElseThrow();
        assertThat(m.level()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.word()).isEqualTo("отсасыватель");
        assertThat(m.similar()).extracting(SimilarProductRef::name).anyMatch(n -> n.startsWith("Аппарат для аспирации дыма"));
    }

    @Test
    void genericSuctionSpec_notCopied() {
        LotMatch m = match("Медицинские изделия", new LotInput(1, "Отсасыватель", "хирургический",
                "Отсасыватель медицинский электрический. Производительность не менее 20 л/мин. Банка 2 л. Шнур питания. "
                        + "Руководство по эксплуатации. Гарантия 12 месяцев."), new LotInput(2, "Бинт", "марлевый", null))
                .stream().filter(x -> x.groupId() == 10).findFirst().orElseThrow();
        assertThat(m.level()).isEqualTo(ProductMatchLevel.TYPE);
    }

    @Test
    void brandInLot_namedByBrandGroup() {
        List<LotMatch> m = match("Приобретение запасных частей", new LotInput(1, "Комплектующие для аппарата Фотек",
                "согласно технической спецификации", null));
        assertThat(m).extracting(LotMatch::groupId, LotMatch::level).containsExactly(org.assertj.core.groups.Tuple.tuple(1L, ProductMatchLevel.NAMED));
        assertThat(m.get(0).evidence()).isEqualTo("в названии лота: фотек");
    }

    @Test
    void modelCodeInSpec_named() {
        LotMatch m = match("Медицинская техника", new LotInput(1, "Электрокардиограф", "12-канальный",
                "Электрокардиограф ЭК12Т-01 или эквивалент, 12 отведений")).stream().filter(x -> x.groupId() == 2).findFirst().orElseThrow();
        assertThat(m.level()).isEqualTo(ProductMatchLevel.NAMED);
        assertThat(m.evidence()).isEqualTo("в ТЗ: эк12т-01");
        assertThat(m.productId()).isNotNull();
    }

    @Test
    void orionOutsideItsGroup_notNamed() {
        assertThat(match("Реагенты", new LotInput(1, "Набор реагентов Orion Diagnostica", "орион", null))).isEmpty();
    }
}
