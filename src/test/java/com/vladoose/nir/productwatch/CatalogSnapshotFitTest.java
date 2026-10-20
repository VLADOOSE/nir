package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.service.productwatch.CatalogSnapshot;
import com.vladoose.nir.service.productwatch.CatalogProduct;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Кандидаты сверки ТЗ (задача 14 плана 2026-10-20-product-spec-fit, калибровка): товары того же вида — слово группы в
 * названии. Бренд группы («аксион») тянет в группу всю продукцию бренда, категория — принадлежности: на наборе ЭКГ-ТЗ
 * сверялось с облучателем ОФТН-03, электрокоагулятор — с кабелем нейтрального электрода.
 */
class CatalogSnapshotFitTest {

    static SiteProduct p(long id, String name, String brand, String category) {
        return SiteProduct.builder().id(id).market(Market.KZ).siteId(UUID.randomUUID()).name(name).brand(brand).category(category)
                .syncedAt(OffsetDateTime.now()).build();
    }

    @Test
    void fitCandidates_byKindInName_notByBrandOrCategory() {
        ProductGroup ecg = ProductFixtures.group(1, "Электрокардиографы (Аксион)", List.of("электрокардиограф"), List.of(), List.of(),
                List.of("аксион"));
        ProductGroup esu = ProductFixtures.group(2, "Электрохирургия (Фотек)", List.of("электрохирургический"), List.of(), List.of(),
                List.of("фотек"));
        CatalogSnapshot s = CatalogSnapshot.build(List.of(ecg, esu), List.of(
                p(10, "Электрокардиограф ЭК12Т-01-«Р-Д»", "Аксион", "Функциональная диагностика"),
                p(11, "Облучатель ОФТН-03 «Аксион»", "Аксион", "Физиотерапия"),
                p(12, "ВЧ электрохирургический блок ЭХВЧ-80-03-«ФОТЕК»", "Фотек", "Электрохирургическое оборудование"),
                p(13, "Кабель нейтрального электрода с разъёмом «джек»", "Фотек", "Электрохирургическое оборудование")), Map.of(), Set.of());
        assertThat(s.groupCards(1)).extracting(ProductCard::productId).containsExactly(10L);
        assertThat(s.groupCards(2)).extracting(ProductCard::productId).containsExactly(12L);
        // отметки по бренду (★ назван наш товар) — как были: облучатель и кабель в товарах групп остаются
        assertThat(s.groupProducts(1)).extracting(CatalogProduct::id).contains(10L, 11L);
        assertThat(s.groupProducts(2)).extracting(CatalogProduct::id).contains(12L, 13L);
    }

    /**
     * Уточнения и исключения группы — и к товару (как к лоту): уточнение — в названии или кратком описании, исключение — в
     * названии. Облучатель фототерапии ОФТН — не кандидат для бактерицидного ТЗ (на наборе — «◐ похоже: ОФТН-420/470-02»
     * у рециркулятора 17822670-1), бактерицидный — не кандидат для фототерапии; ЭКГ-кабель «с электродами» — не кандидат
     * для нейтральных электродов.
     */
    @Test
    void fitCandidates_groupRequireAndExcludeApplyToProduct() {
        ProductGroup uv = ProductFixtures.group(1, "Облучатели бактерицидные", List.of("облучатель"), List.of("бактерицид", "ультрафиолет"),
                List.of(), List.of());
        ProductGroup photo = ProductFixtures.group(2, "Облучатели фототерапевтические", List.of("облучатель"), List.of("фототерап", "новорожд"),
                List.of(), List.of());
        ProductGroup neutral = ProductFixtures.group(3, "Нейтральные электроды", List.of("электрод"), List.of("нейтральн"),
                List.of("экг"), List.of());
        SiteProduct ofth = p(11, "Облучатель ОФТН-03 «Аксион»", "Аксион", "Неонатология");
        ofth.setShortDescription("Облучатель фототерапевтический для лечения желтухи новорождённых.");
        CatalogSnapshot s = CatalogSnapshot.build(List.of(uv, photo, neutral), List.of(
                p(10, "Бактерицидный облучатель ОБН 150 открытый", "ЭМА", "Обеззараживание"),
                ofth,
                p(12, "Электрод нейтральный (возвратный), электрохирургический", "Фотек", "Электрохирургия"),
                p(13, "Кабель ЭКГ с одноразовыми нейтральными электродами", "Монитор", "Диагностика")), Map.of(), Set.of());
        assertThat(s.groupCards(1)).extracting(ProductCard::productId).containsExactly(10L);
        assertThat(s.groupCards(2)).extracting(ProductCard::productId).containsExactly(11L);
        assertThat(s.groupCards(3)).extracting(ProductCard::productId).containsExactly(12L);
    }
}
