package com.vladoose.nir.integration.skpharmacy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Парсер на РЕАЛЬНЫХ фикстурах, снятых с fms.ecc.kz (search.html — searchanno; lots.html — объявление 521464). */
class SkPharmacyHtmlParserTest {

    private String fixture(String name) throws IOException {
        try (var is = getClass().getResourceAsStream("/skpharmacy/" + name)) {
            assertThat(is).as("фикстура %s", name).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parseSearch_realFixture_10announces_firstFields() throws IOException {
        List<SkAnnounce> list = SkPharmacyHtmlParser.parseSearch(fixture("search.html"));
        assertThat(list).hasSize(10);
        SkAnnounce a = list.get(0);
        assertThat(a.announceId()).isEqualTo("521464");
        assertThat(a.numberAnno()).isEqualTo("521464-1");
        assertThat(a.nameRu().toLowerCase()).contains("медицинск");
        assertThat(a.purchaseType()).isEqualTo("Тендер");
        assertThat(a.lotsCount()).isEqualTo(12);
        assertThat(a.totalSum()).isEqualByComparingTo("15085999992.00");
        assertThat(a.status()).isEqualTo("Опубликовано");
    }

    @Test
    void parseLots_realFixture_deviceLots() throws IOException {
        List<SkLot> lots = SkPharmacyHtmlParser.parseLots(fixture("lots.html"));
        assertThat(lots).isNotEmpty();
        assertThat(lots).anySatisfy(l -> assertThat(l.name().toLowerCase()).contains("томограф"));
        SkLot first = lots.get(0);
        assertThat(first.name()).isNotBlank();
        assertThat(first.quantity()).isNotNull();
        assertThat(first.unitPrice()).isNotNull();
        // код лота (td[1]) = реальный № на площадке — ключ связи с файлами ТЗ (modal «Номер лота»)
        assertThat(first.code()).isEqualTo("1040409-Т1");
    }

    /**
     * Вторая вёрстка lots-таблицы («приказ ЕД», объявление 522204): первая ячейка — НЕ номер по порядку,
     * а код лота «4875223-Д_ЛС_МИ1», наименование лежит в колонке «МНН», цена — в «Цена ЕД для закупа»,
     * колонки «Количество» нет вовсе. Раньше отсеивались ВСЕ строки до единой → тендер сохранялся без лотов.
     */
    @Test
    void parseLots_edOrderLayout_nameFromInn_priceFromPurchaseColumn() throws IOException {
        List<SkLot> lots = SkPharmacyHtmlParser.parseLots(fixture("lots-ed-order.html"));
        assertThat(lots).hasSize(20);
        SkLot first = lots.get(0);
        assertThat(first.code()).isEqualTo("4875223-Д_ЛС_МИ1");
        assertThat(first.name()).startsWith("Шприцы инъекционные, безопасные, 3-х компонентные");
        assertThat(first.unitPrice()).isEqualByComparingTo("18.93");   // «Цена ЕД для закупа», не «Предельная цена МЗ РК» (20.36)
        assertThat(first.quantity()).isNull();                          // колонки «Количество» в этой вёрстке нет
        // профиль West-Med — то, что терялось молча
        assertThat(lots).anySatisfy(l -> assertThat(l.name()).contains("эндопротезирования тазобедренного"));
    }

    /**
     * Третья вёрстка («долгосрочные договоры», объявление 513532): код лота «4809097-ИОИ1» в первой ячейке,
     * наименование — колонка МНН (а не «по торговому наименованию» и не «наименование поставщика»).
     */
    @Test
    void parseLots_longTermLayout_nameFromInnColumnNotSupplier() throws IOException {
        List<SkLot> lots = SkPharmacyHtmlParser.parseLots(fixture("lots-longterm.html"));
        assertThat(lots).hasSize(8);
        SkLot first = lots.get(0);
        assertThat(first.code()).isEqualTo("4809097-ИОИ1");
        assertThat(first.name()).startsWith("Комплект для родов стерильный");
        assertThat(first.quantity()).isEqualTo(487);
        assertThat(first.unitPrice()).isEqualByComparingTo("14000");
    }

    /**
     * Четвёртая вёрстка (объявление 522744, «предельные цены»): шапка таблицы свёрстана обычными {@code <td>},
     * тега {@code <th>} на странице нет вообще. Строка заголовка при этом не должна попасть в лоты.
     */
    @Test
    void parseLots_headerRowMadeOfTd_stillResolvedAndNotTakenForLot() throws IOException {
        List<SkLot> lots = SkPharmacyHtmlParser.parseLots(fixture("lots-price-limit.html"));
        assertThat(lots).hasSize(2);
        assertThat(lots).noneSatisfy(l -> assertThat(l.name()).isEqualToIgnoringCase("МНН"));   // шапка — не лот
        SkLot first = lots.get(0);
        assertThat(first.code()).isEqualTo("4876723-PNLTS1");
        assertThat(first.name()).isEqualTo("Урсодезоксихолевая кислота");
        assertThat(first.unitPrice()).isEqualByComparingTo("226.52");   // «Цена ЕД для закупа», не «Предельная по ТН» (252)
        assertThat(first.quantity()).isEqualTo(22740);                  // «Количество к закупу»
    }

    /** Пагинация lots-вкладки: идём дальше, только пока в пейджере есть ссылка на СЛЕДУЮЩУЮ страницу. */
    @Test
    void hasNextLotsPage_trueWhileNextLinkExists_falseOnLastPage() throws IOException {
        assertThat(SkPharmacyHtmlParser.hasNextLotsPage(fixture("lots-ed-order.html"), 1)).isTrue();
        // последняя страница 522204: в пейджере ссылки только назад (page=2), ссылки на page=4 нет
        assertThat(SkPharmacyHtmlParser.hasNextLotsPage(fixture("lots-ed-order-last.html"), 3)).isFalse();
        // одностраничное объявление — пейджера нет вовсе
        assertThat(SkPharmacyHtmlParser.hasNextLotsPage(fixture("lots-longterm.html"), 1)).isFalse();
    }

    @Test
    void parse_empty_null_safe() {
        assertThat(SkPharmacyHtmlParser.parseSearch("")).isEmpty();
        assertThat(SkPharmacyHtmlParser.parseSearch(null)).isEmpty();
        assertThat(SkPharmacyHtmlParser.parseLots("<html><body>нет таблицы</body></html>")).isEmpty();
        assertThat(SkPharmacyHtmlParser.hasNextLotsPage(null, 1)).isFalse();
        assertThat(SkPharmacyHtmlParser.hasNextLotsPage("<html><body>нет пейджера</body></html>", 1)).isFalse();
    }

    /**
     * Гард от молчаливой подмены колонок: в неизвестной вёрстке лучше вернуть пусто (сбой виден в счётчике
     * прогона и лоты на месте — §7 writer), чем разложить строки по угаданным позициям.
     */
    @Test
    void parseLots_unknownHeaders_returnsEmptyRatherThanGuessing() {
        String html = """
                <table><tr><th>Колонка А</th><th>Колонка Б</th><th>Колонка В</th>
                <th>Колонка Г</th><th>Колонка Д</th><th>Колонка Е</th></tr>
                <tr><td>1</td><td>X-1</td><td>Нечто</td><td>10</td><td>2</td><td>20</td></tr></table>
                """;
        assertThat(SkPharmacyHtmlParser.parseLots(html)).isEmpty();
    }

    /** general.html — объявление 521304 (АО «КАЗМЕДТЕХ», лизингодатель): реальное ФИО секретаря + метка «Лизингодатель». */
    @Test
    void parseGeneral_lessor_binAddressKatoEmailContact() throws IOException {
        SkGeneral g = SkPharmacyHtmlParser.parseGeneral(fixture("general.html"));
        assertThat(g).isNotNull();
        assertThat(g.customerBin()).isEqualTo("101240007453");
        assertThat(g.legalAddress()).contains("Астана").contains("711510000");
        assertThat(g.regionKato()).isEqualTo("711510000");        // 9-значный КАТО из адреса, не 6-значный индекс
        assertThat(g.contactEmail()).isEqualTo("g.stepanenko@kmtlc.kz");
        assertThat(g.contactName()).isEqualTo("СТЕПАНЕНКО ГЕННАДИЙ");
    }

    /** general-distributor.html — 521464 (ТОО «СК-Фармация», единый дистрибьютор): метка организатора ДРУГАЯ. */
    @Test
    void parseGeneral_singleDistributor_labelVariantStillParsed() throws IOException {
        SkGeneral g = SkPharmacyHtmlParser.parseGeneral(fixture("general-distributor.html"));
        assertThat(g).isNotNull();
        assertThat(g.customerBin()).isEqualTo("090340007747");
        assertThat(g.regionKato()).isEqualTo("711210000");
        assertThat(g.contactEmail()).isEqualTo("t.omirbay@sk-pharmacy.kz");
        assertThat(g.legalAddress()).contains("Астана");
    }

    @Test
    void parseGeneral_null_safe() {
        assertThat(SkPharmacyHtmlParser.parseGeneral(null)).isNull();
        assertThat(SkPharmacyHtmlParser.parseGeneral("")).isNull();
        SkGeneral none = SkPharmacyHtmlParser.parseGeneral("<html><body>нет полей</body></html>");
        assertThat(none.customerBin()).isNull();
        assertThat(none.legalAddress()).isNull();
    }
}
