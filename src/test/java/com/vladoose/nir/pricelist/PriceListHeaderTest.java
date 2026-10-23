package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PriceListHeader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceListHeaderTest {

    @Test
    void datesFromPriceLines_notFromRegistrationText() {
        PriceListHeader h = PriceListHeader.of(List.of(
                "2023 Действителен до 03.05.2028 года",                              // срок РУ — не срок прайса
                "• Указанный прайс-лист актуален с 01 сентября 2026 года по 31 декабря 2026 года."), "прайс.pdf");
        assertThat(h.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(h.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void retail_glued_fileNameDate_terms() {
        PriceListHeader orion = PriceListHeader.of(List.of("РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "действительны с1 ноября 2025 г",
                "Гарантийный срок на все оборудование составляет 2 года."), "Прайс стерилизаторы 01_11_2025.doc");
        assertThat(orion.retail()).isTrue();
        assertThat(orion.listDate()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(orion.terms()).contains("Гарантийный срок");
        PriceListHeader byName = PriceListHeader.of(List.of("Прайс-лист"), "[RU, RUB, KZ] Прайс-лист (01.09.2026).pdf");
        assertThat(byName.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(byName.retail()).isFalse();
        PriceListHeader terms = PriceListHeader.of(List.of("• Предоплата 100%;", "• Сертификат СТ-1 стоит 11000 рублей за партию;",
                "Больше информации на сайте"), null);
        assertThat(terms.terms()).isEqualTo("• Предоплата 100%;\n• Сертификат СТ-1 стоит 11000 рублей за партию;");
    }

    @Test
    void currencyFromText_mixedIsMixed() {
        assertThat(PriceListHeader.currency(List.of("Цены в рублях"))).isEqualTo("RUB");
        assertThat(PriceListHeader.currency(List.of("Цены в рублях", "Доставка 100 $"))).isEqualTo("MIXED");
        assertThat(PriceListHeader.currency(List.of("Прайс-лист"))).isNull();
    }

    @Test
    void defaultColumn_dealerThenDiscountThenWholesaleThenCheapest() {
        List<PriceListColumn> axion = List.of(new PriceListColumn(0, "Оптовая цена, RUB, без НДС"),
                new PriceListColumn(1, "Цена со скидкой при подписании дилерского контракта"));
        assertThat(PriceListHeader.defaultColumn(axion, List.of())).isEqualTo(1);
        // «дилер» важнее «со скидкой», даже правее
        List<PriceListColumn> dealer = List.of(new PriceListColumn(0, "Цена со скидкой 5%"), new PriceListColumn(1, "Цена для дилеров"));
        assertThat(PriceListHeader.defaultColumn(dealer, List.of())).isEqualTo(1);
        List<PriceListColumn> plain = List.of(new PriceListColumn(0, "Цена А"), new PriceListColumn(1, "Цена Б"));
        List<ParsedPriceRow> rows = List.of(
                new ParsedPriceRow(1, null, "x", null, List.of(new BigDecimal("100"), new BigDecimal("90")), null),
                new ParsedPriceRow(2, null, "y", null, List.of(new BigDecimal("50"), new BigDecimal("40")), null));
        assertThat(PriceListHeader.defaultColumn(plain, rows)).isEqualTo(1);
        assertThat(PriceListHeader.defaultColumn(List.of(new PriceListColumn(0, "Цена")), rows)).isZero();
        assertThat(PriceListHeader.defaultColumn(List.of(), rows)).isNull();
    }
}
