package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.ReplyTableReader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Правила строк готового КП (спека 2026-10-19 §4.2; решение при планировании 5). */
class OfferFileLinesTest {

    static final List<LineField> FATHER_ROLES = List.of(LineField.NUMBER, LineField.NAME, LineField.UNIT, LineField.QUANTITY,
            LineField.PRICE, LineField.VAT, LineField.SUM);

    static ReplyTableReader.ReadTable table(String currency, BigDecimal total, List<String> headers, List<LineField> roles,
                                           List<List<String>> rows) {
        List<ReplyTable.Column> cols = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) cols.add(new ReplyTable.Column(i, headers.get(i), roles.get(i)));
        List<ReplyTable.Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.add(new ReplyTable.Row(i + 2, rows.get(i)));
        return new ReplyTableReader.ReadTable(new ReplyTable("таблица 1", 1, roles.size(), currency, cols, out), total);
    }

    static List<String> same(String text, int n) {
        String[] a = new String[n];
        Arrays.fill(a, text);
        return List.of(a);
    }

    @Test
    void mergedRowIsSection_notAnItemPricedOne_sectionWithoutItemsDropped() {   // КП «ОЗОН»
        var t = table("KZT", null, ReadyOfferFixtures.FATHER_HEADER, FATHER_ROLES, List.of(
                same("1 этаж", 7),
                List.of("1", "Консоль МК-НД-800", "шт", "2", "2 386 800,00", "В том числе НДС 16%", "4 773 600,00"),
                same("Запас", 7)));
        List<OfferFileLine> lines = OfferFileLines.of(List.of(t), "KZT").lines();
        assertThat(lines).extracting(OfferFileLine::kind).containsExactly(ClientOfferItemKind.SECTION, ClientOfferItemKind.ITEM);
        assertThat(lines.get(0).name()).isEqualTo("1 этаж");
        OfferFileLine item = lines.get(1);
        assertThat(item.name()).isEqualTo("Консоль МК-НД-800");
        assertThat(item.quantity()).isEqualByComparingTo("2");
        assertThat(item.unit()).isEqualTo("шт");
        assertThat(item.price()).isEqualByComparingTo("2386800");
        assertThat(item.vat()).isEqualTo("В том числе НДС 16%");
    }

    @Test
    void includedRowAndItsSectionKept_registrationRowsSkipped() {
        var t = table("KZT", null, List.of("Наименование", "Стоимость за 1 шт., тенге"), List.of(LineField.NAME, LineField.PRICE), List.of(
                List.of("ЭКГ простая комплектация", "1 090 800,00"),
                same("Наличие документации в РК", 2),
                List.of("Регистрационное удостоверение", "РК-МТ-5№017777 (бессрочно)"),
                same("Сервис", 2),
                List.of("Гарантия 12 месяцев", "Включено в стоимость")));
        List<OfferFileLine> lines = OfferFileLines.of(List.of(t), "KZT").lines();
        assertThat(lines).extracting(OfferFileLine::kind)
                .containsExactly(ClientOfferItemKind.ITEM, ClientOfferItemKind.SECTION, ClientOfferItemKind.INCLUDED);
        assertThat(lines).extracting(OfferFileLine::name)
                .containsExactly("ЭКГ простая комплектация", "Сервис", "Гарантия 12 месяцев");
    }

    @Test
    void sumOnlyTablePriceIsSumDividedByQuantity() {
        var t = table(null, new BigDecimal("60000"), List.of("Наименование", "Кол-во", "Сумма, тг"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.SUM), List.of(List.of("Ширма", "2", "60 000")));
        OfferFileLines.Lines l = OfferFileLines.of(List.of(t), "KZT");
        assertThat(l.lines().get(0).price()).isEqualByComparingTo("30000");
        assertThat(l.fileTotal()).isEqualByComparingTo("60000");
    }

    @Test
    void foreignCurrencyItemsWithoutPrice() {
        var t = table("RUB", null, List.of("Наименование", "Цена, руб."), List.of(LineField.NAME, LineField.PRICE),
                List.of(List.of("Консоль", "940 000")));
        OfferFileLines.Lines l = OfferFileLines.of(List.of(t), "KZT");
        assertThat(l.lines()).singleElement().satisfies(x -> {
            assertThat(x.kind()).isEqualTo(ClientOfferItemKind.ITEM);
            assertThat(x.price()).isNull();
        });
        assertThat(l.foreignCurrency()).isEqualTo("RUB");
    }

    @Test
    void modelBrandFromOwnColumns_quantityAndUnitDefaults_totalsAcrossTables() {
        var a = table("KZT", new BigDecimal("100"), List.of("Наименование", "Модель", "Производитель", "Цена, тг"),
                List.of(LineField.NAME, LineField.ARTICLE, LineField.MANUFACT, LineField.PRICE),
                List.of(List.of("Центрифуга", "СМ-6М", "ЭЛМИ", "1 360 000")));
        var b = table("KZT", new BigDecimal("50"), List.of("Наименование", "Цена, тг"), List.of(LineField.NAME, LineField.PRICE),
                List.of(List.of("Ротор", "50")));
        OfferFileLines.Lines l = OfferFileLines.of(List.of(a, b), "KZT");
        OfferFileLine c = l.lines().get(0);
        assertThat(c.model()).isEqualTo("СМ-6М");
        assertThat(c.brand()).isEqualTo("ЭЛМИ");
        assertThat(c.quantity()).isEqualByComparingTo("1");
        assertThat(c.unit()).isEqualTo("шт");
        assertThat(l.fileTotal()).isEqualByComparingTo("150");
    }

    @Test
    void vatFromColumnTextOnlyMarketRates() {
        List<BigDecimal> rates = new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null));
        assertThat(OfferFileLines.vat("В том числе НДС 5%", rates)).isEqualTo(new OfferFileLines.VatPick(true, new BigDecimal("5")));
        assertThat(OfferFileLines.vat("16 %", rates).rate()).isEqualByComparingTo("16");
        assertThat(OfferFileLines.vat("Без НДС", rates)).isEqualTo(new OfferFileLines.VatPick(true, null));
        assertThat(OfferFileLines.vat("12%", rates).found()).isFalse();
        assertThat(OfferFileLines.vat("15 085,71", rates).found()).isFalse();   // «Сумма НДС» — не ставка
        assertThat(OfferFileLines.vat(null, rates).found()).isFalse();
    }
}
