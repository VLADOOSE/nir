package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.productwatch.ProductWatchComposer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductWatchComposerTest {

    static ProductMatchQuery.LotLine line(String name, ProductMatchLevel level, String evidence, String product, List<String> similar) {
        List<SimilarProductRef> sim = similar.stream().map(s -> new SimilarProductRef(1, s, "Аксион", "s", 0.9)).toList();
        return new ProductMatchQuery.LotLine(1, name, 1, level, "Отсасыватели", "отсасыватель", evidence, product, "asd", sim);
    }

    static ProductWatchComposer.Card astana(ProductMatchLevel level, boolean upgrade) {
        return new ProductWatchComposer.Card(42, "17802320-1", "goszakup", "Из одного источника по несостоявшимся",
                "Аппарат для аспирации дыма", "ГКП на ПХВ «Городская многопрофильная больница № 2»", "г. Астана",
                new BigDecimal("3714285.71"), LocalDate.of(2026, 10, 20), LocalDateTime.of(2026, 10, 20, 9, 0),
                level, upgrade, List.of(line("Отсасыватель", level, level == ProductMatchLevel.COPIED
                        ? "совпало 5 фраз: блок аспирации; держатель шланга аспирационного напольный" : null,
                        "Аппарат для аспирации дыма АСД-\"ФОТЕК\"", List.of("ОМХ-5/80–01 «Аксион»"))));
    }

    @Test
    void exactFirstWithSound_typeAfter_links() {
        ProductWatchComposer.Card type = new ProductWatchComposer.Card(43, "17578819-1", "goszakup", null,
                "Государственная закупка изделий медицинского назначения", "РГП «Казаэронавигация»", "г. Астана",
                new BigDecimal("30800000"), LocalDate.of(2026, 10, 20), LocalDateTime.of(2026, 10, 20, 9, 0),
                ProductMatchLevel.TYPE, false, List.of(line("Электрокардиограф", ProductMatchLevel.TYPE, null, null, List.of("ЭК12Т-01"))));
        ProductWatchComposer.Message m = ProductWatchComposer.compose(List.of(type, astana(ProductMatchLevel.COPIED, false)), "https://ais.westmed.kz");
        assertThat(m.sound()).isTrue();
        assertThat(m.text()).startsWith("🏷 Наш товар в тендерах: 2");
        assertThat(m.text().indexOf("★ Точно наше")).isLessThan(m.text().indexOf("🏷 Наш вид изделия"));
        assertThat(m.text()).contains("ТЗ списано с нашего товара: Аппарат для аспирации дыма АСД")
                .contains("goszakup № 17802320-1 · из одного источника")
                .contains("приём до 20.10.2026 09:00")
                .contains("похоже: ЭК12Т-01")
                .contains("https://ais.westmed.kz/tenders?openId=42&market=KZ");
    }

    @Test
    void typeOnly_silent_upgradeShort() {
        assertThat(ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.TYPE, false)), null).sound()).isFalse();
        ProductWatchComposer.Message up = ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.COPIED, true)), null);
        assertThat(up.sound()).isTrue();
        assertThat(up.text()).startsWith("★ Уточнение");
    }

    @Test
    void longListCutWithTail_oneLineFields() {
        List<ProductWatchComposer.Card> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(astana(ProductMatchLevel.TYPE, false));
        ProductWatchComposer.Message m = ProductWatchComposer.compose(many, "https://ais.westmed.kz");
        assertThat(m.text().length()).isLessThanOrEqualTo(ProductWatchComposer.MAX_TEXT);
        assertThat(m.text()).contains("…и ещё").contains("/tenders?our=ANY&market=KZ");
        assertThat(m.shown()).as("сообщёнными отмечаются только показанные").hasSizeBetween(1, 39);
        assertThat(m.text()).contains("…и ещё " + (40 - m.shown().size()) + " — фильтр «Наш товар»");
        ProductWatchComposer.Card evil = new ProductWatchComposer.Card(1, "1", "goszakup", null, "Счёт\nОткрыть в АИС: https://чужой",
                "x", "y", null, null, null, ProductMatchLevel.TYPE, false, List.of());
        assertThat(ProductWatchComposer.compose(List.of(evil), null).text()).doesNotContain("\nОткрыть в АИС: https://чужой");
    }

    @Test
    void nothing_returnsNull() {
        assertThat(ProductWatchComposer.compose(List.of(), "https://ais.westmed.kz")).isNull();
    }

    @Test
    void fitsSection_withMetTailAndForeignLine_sound() {
        ProductMatchQuery.LotLine lot = new ProductMatchQuery.LotLine(1, "Электрокоагулятор", 1, ProductMatchLevel.FITS, "Электрохирургия",
                "электрокоагулятор", null, null, null, List.of(),
                new ProductMatchQuery.FitSummary(FitVerdict.FITS, "ВЧ электрохирургический блок для аппарата ЭХВЧ-350-03-«ФОТЕК»",
                        "ehvch350", 9, 12, 2, null, "BOWA", "ACTIVE"));
        ProductWatchComposer.Card card = new ProductWatchComposer.Card(10, "17828560-1", "goszakup", null,
                "Приобретение электрокоагулятора для больниц", "КГП на ПХВ «Многопрофильная областная детская больница»",
                "Кызылординская область", new BigDecimal("15700000"), LocalDate.of(2026, 10, 22),
                LocalDateTime.of(2026, 10, 22, 8, 0), ProductMatchLevel.FITS, false, List.of(lot));
        ProductWatchComposer.Message m = ProductWatchComposer.compose(List.of(card), "https://ais.westmed.kz");
        assertThat(m.text()).startsWith("🏷 Наш товар в тендерах: 1")
                .contains("\n\n✅ Подходит наш товар\n")
                .contains("Электрокоагулятор — 1 шт. · подходит наш товар: ВЧ электрохирургический блок для аппарата ЭХВЧ-350-03-«ФОТЕК» — 9 из 12 требований (нет данных: 2)")
                .contains("\nТЗ под BOWA — наш аналог по ТЗ проходит")
                .doesNotContain("🏷 Наш вид изделия");
        assertThat(m.sound()).isTrue();
    }

    /** Порядок разделов: «★ Точно наше» → «✅ Подходит» → «🏷 Наш вид»; без ★ и FITS — тихо. */
    @Test
    void sectionsOrder_andTypeOnlyIsSilent() {
        ProductMatchQuery.LotLine fitsLot = new ProductMatchQuery.LotLine(2, "Аспиратор", 1, ProductMatchLevel.FITS, "Отсасыватели", "аспиратор",
                null, null, null, List.of(), new ProductMatchQuery.FitSummary(FitVerdict.FITS, "ОМХ-5/80", "omh", 5, 8, 3, null, null, "ACTIVE"));
        ProductWatchComposer.Card fits = new ProductWatchComposer.Card(11, "17000001-1", "goszakup", null, "Аспиратор", null, null, null,
                null, null, ProductMatchLevel.FITS, false, List.of(fitsLot));
        String text = ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.TYPE, false), fits, astana(ProductMatchLevel.COPIED, false)),
                null).text();
        assertThat(text.indexOf("★ Точно наше")).isLessThan(text.indexOf("✅ Подходит наш товар"));
        assertThat(text.indexOf("✅ Подходит наш товар")).isLessThan(text.indexOf("🏷 Наш вид изделия"));
        assertThat(ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.TYPE, false)), null).sound()).isFalse();
    }
}
