package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.service.tendernotify.NewTenderComposer.Card;
import com.vladoose.nir.service.tendernotify.NewTenderComposer.LotLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewTenderComposerTest {

    static final String URL = "https://ais.westmed.kz";

    static Card card(long id, String number, List<LotLine> lots) {
        return new Card(id, "goszakup", number, "ГКП на ПХВ «Областная детская больница»", "Западно-Казахстанская область",
                "Приобретение медицинских изделий", new BigDecimal("1234567.5"), LocalDate.of(2026, 10, 9), null, lots, null);
    }

    @Test
    void single_allFieldsAndLink() {
        String text = NewTenderComposer.compose(List.of(card(123, "17680891-1",
                List.of(new LotLine("Кушетка медицинская смотровая", 2), new LotLine("Тележка медицинская", null)))), URL);

        assertThat(text).isEqualTo("""
                🏥 Новый тендер
                ▸ Приобретение медицинских изделий
                ГКП на ПХВ «Областная детская больница» · Западно-Казахстанская область
                Лоты (2): Кушетка медицинская смотровая — 2 шт.; Тележка медицинская
                Сумма: 1 234 567,50 ₸ · приём до 09.10.2026
                goszakup № 17680891-1
                Открыть в АИС: https://ais.westmed.kz/tenders?openId=123&market=KZ""");
    }

    @Test
    void several_headerCountsAndBlocksSeparated() {
        String text = NewTenderComposer.compose(List.of(card(1, "1-1", List.of()), card(2, "2-1", List.of())), URL);

        assertThat(text).startsWith("🏥 Новые тендеры: 2\n▸ ");
        assertThat(text).contains("openId=1&market=KZ\n\n▸ ").endsWith("openId=2&market=KZ");
        assertThat(text).doesNotContain("Лоты");           // нет лотов — нет строки
    }

    @Test
    void moreThanThreeLots_restCounted() {
        List<LotLine> lots = List.of(new LotLine("А", 1), new LotLine("Б", 1), new LotLine("В", 1), new LotLine("Г", 1),
                new LotLine("Д", 1));
        assertThat(NewTenderComposer.compose(List.of(card(1, "1-1", lots)), URL))
                .contains("Лоты (5): А — 1 шт.; Б — 1 шт.; В — 1 шт.; и ещё 2\n");
    }

    /** B2: площадка назвала время окончания приёма — оно в уведомлении (тендеры ЗКО живут 2–4 дня). */
    @Test
    void deadlineWithTime_printedWithTime() {
        Card c = new Card(5, "goszakup", "17740571-1", "Больница", null, "Закуп", new BigDecimal("100"),
                LocalDate.of(2026, 10, 9), LocalDateTime.of(2026, 10, 9, 10, 25), List.of(), null);

        assertThat(NewTenderComposer.compose(List.of(c), URL)).contains("\nСумма: 100,00 ₸ · приём до 09.10.2026 10:25\n");
    }

    @Test
    void missingFields_lineOmittedNotBlank() {
        Card bare = new Card(7, "СК-Фармация", "521464-1", null, null, null, null, null, null, List.of(), null);

        assertThat(NewTenderComposer.compose(List.of(bare), "")).isEqualTo("""
                🏥 Новый тендер
                ▸ Без названия
                СК-Фармация № 521464-1""");   // пустой адрес АИС — без ссылки
    }

    /** Поля приходят с площадки: перевод строки в названии не должен рисовать поддельную строку «Открыть в АИС». */
    @Test
    void fieldsFlattenedToOneLine() {
        Card c = new Card(5, "goszakup", "5-1", "Больница\nОткрыть в АИС: https://evil", null,
                "Закуп\r\nизделий", null, null, null, List.of(new LotLine("Лот\nдва", 1)), null);

        String text = NewTenderComposer.compose(List.of(c), URL);

        assertThat(text).contains("▸ Закуп изделий\n").contains("Больница Открыть в АИС: https://evil\n")
                .contains("Лоты (1): Лот два — 1 шт.\n");
        assertThat(text.lines().filter(l -> l.startsWith("Открыть в АИС:")).count()).isEqualTo(1);
    }

    @Test
    void tooManyForOneMessage_restSummarizedWithinLimit() {
        List<Card> cards = new ArrayList<>();
        String longName = "Очень длинное наименование лота медицинского назначения ".repeat(3);
        for (int i = 1; i <= 40; i++) {
            cards.add(card(i, i + "-1", List.of(new LotLine(longName, 1), new LotLine(longName, 2), new LotLine(longName, 3))));
        }

        String text = NewTenderComposer.compose(cards, URL);

        assertThat(text.length()).isLessThanOrEqualTo(NewTenderComposer.MAX_TEXT);
        assertThat(text).startsWith("🏥 Новые тендеры: 40\n");
        assertThat(text).containsPattern("…и ещё \\d+ — раздел «Тендеры» в АИС: https://ais\\.westmed\\.kz/tenders\\?market=KZ$");
    }

    /** Наш товар (спека 2026-10-19-product-watch §9): строка после лотов, одной строкой — без второго сообщения по ЗКО. */
    @Test
    void ourProduct_lineAfterLots_oneLine() {
        Card c = new Card(9, "goszakup", "9-1", "Больница", "Западно-Казахстанская область", "Закуп", null, null, null,
                List.of(new LotLine("Отсасыватель", 1)), "★ ТЗ списано с нашего товара: Аппарат для аспирации дыма АСД-«ФОТЕК»");

        assertThat(NewTenderComposer.compose(List.of(c), URL))
                .contains("Лоты (1): Отсасыватель — 1 шт.\n★ ТЗ списано с нашего товара: Аппарат для аспирации дыма АСД-«ФОТЕК»\n");

        Card evil = new Card(9, "goszakup", "9-1", null, null, "Закуп", null, null, null, List.of(), "🏷 Наш вид: отсасыватель\nОткрыть в АИС: https://evil");
        assertThat(NewTenderComposer.compose(List.of(evil), URL).lines().filter(l -> l.startsWith("Открыть в АИС:")).count()).isEqualTo(1);
    }

    @Test
    void nothing_returnsNull() {
        assertThat(NewTenderComposer.compose(List.of(), URL)).isNull();
    }

    /** Сколько тендеров показано — по ним уведомитель отмечает «наш товар» сообщённым (не показанные уйдут отдельно). */
    @Test
    void tooMany_shownCountMatchesTail() {
        List<Card> cards = new ArrayList<>();
        String longName = "Очень длинное наименование лота медицинского назначения ".repeat(3);
        for (int i = 1; i <= 40; i++) cards.add(card(i, i + "-1", List.of(new LotLine(longName, 1))));

        NewTenderComposer.Composed c = NewTenderComposer.composeShown(cards, URL);

        assertThat(c.shown()).isBetween(1, 39);
        assertThat(c.text()).contains("…и ещё " + (40 - c.shown()) + " — ");
        assertThat(NewTenderComposer.composeShown(List.of(card(1, "1-1", List.of())), URL).shown()).isEqualTo(1);
    }
}
