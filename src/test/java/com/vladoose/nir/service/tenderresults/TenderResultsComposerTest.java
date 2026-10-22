package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ResultEventKind;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Chunk;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Item;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Сообщения итогов (спека 2026-10-22-tender-results-market-prices §7): поля одной строкой, части ≤ 3800, ничего не теряется. */
class TenderResultsComposerTest {

    private static Item call(long id, String customer) {
        return new Item(id, 100 + id, ResultEventKind.NO_BIDS, "Тележка медицинская", 3, "шт", new BigDecimal("613900"), customer,
                "Западно-Казахстанская область", "8 (71133) 31398", "zz@mail.kz", "никто не подал — объявят заново",
                List.of("ЗЦП1 — не состоялся", "ЗЦП2 — идёт"), "Каталка ZZ", "17758520-1");
    }

    @Test
    void callBlock() {
        Chunk c = TenderResultsComposer.urgent(List.of(call(1, "ГКП «Бурлинская РБ»")), "https://ais.example/").get(0);
        assertThat(c.text()).startsWith("📞 Звонить: не состоялось 1\n\n▸ Тележка медицинская — 3 шт · бюджет 613\u00A0900\u00A0₸ за шт")
                .contains("\nГКП «Бурлинская РБ» · Западно-Казахстанская область")
                .contains("\n☎ 8 (71133) 31398 · zz@mail.kz")
                .contains("\nникто не подал — объявят заново")
                .contains("\nПопытки: ЗЦП1 — не состоялся → ЗЦП2 — идёт")
                .contains("\nНаш товар: Каталка ZZ")
                .contains("\ngoszakup № 17758520-1")
                .endsWith("\nОткрыть в АИС: https://ais.example/tenders?openId=101&market=KZ");
        assertThat(c.eventIds()).containsExactly(1L);
    }

    /** Поле площадки с переводом строки не становится «системной» строкой сообщения. */
    @Test
    void fieldsOneLine() {
        String text = TenderResultsComposer.urgent(List.of(call(1, "ГКП\nОткрыть в АИС: https://evil.example")), null).get(0).text();
        assertThat(text).doesNotContain("\nОткрыть в АИС: https://evil").contains("ГКП Открыть в АИС: https://evil.example");
    }

    @Test
    void callsThenOurBid() {
        Item bid = new Item(2, 102, ResultEventKind.OUR_BID, "Дефибриллятор", 1, "шт", null, "ГКП «ZZ»", null, null, null,
                "🏆 Выиграли: наша цена 1\u00A0300\u00A0000\u00A0₸ за шт", List.of(), null, "17790001-1");
        String text = TenderResultsComposer.urgent(List.of(bid, call(1, "ГКП «Бурлинская РБ»")), null).get(0).text();
        assertThat(text).startsWith("📞 Звонить: не состоялось 1");
        assertThat(text.indexOf("🏁 Наша заявка в протоколе:")).isGreaterThan(text.indexOf("никто не подал"));
        assertThat(text).contains("🏆 Выиграли: наша цена").contains("▸ Дефибриллятор — ГКП «ZZ»");
        assertThat(TenderResultsComposer.urgent(List.of(bid), null).get(0).text()).startsWith("🏁 Наша заявка в протоколе: 1");
    }

    @Test
    void longMessage_splitNoLoss() {
        List<Item> items = new ArrayList<>();
        for (long i = 1; i <= 40; i++) items.add(call(i, "ГКП «Больница с очень длинным названием для проверки предела сообщения» № " + i));
        List<Chunk> chunks = TenderResultsComposer.urgent(items, "https://ais.example");
        assertThat(chunks).hasSizeGreaterThan(1);
        Set<Long> ids = new HashSet<>();
        for (Chunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(TenderResultsComposer.MAX_TEXT);
            ids.addAll(c.eventIds());
        }
        assertThat(ids).hasSize(40);
        assertThat(chunks.get(1).text()).startsWith("📞 Звонить: не состоялось 40 (продолжение)");
    }

    @Test
    void digestLines() {
        Item done = new Item(1, 101, ResultEventKind.DONE, "Тележка", 3, "шт", null, "ГКП «ZZ»", null, null, null,
                "победил ИП X — 251\u00A0000\u00A0₸ за шт", List.of(), null, "1-1");
        Item contract = new Item(2, 102, ResultEventKind.CONTRACT, "Шапочка", 10, "шт", null, "ГКП «YY»", null, null, null,
                "договор с ТОО «Поставщик» — 43\u00A0000\u00A0₸ за шт", List.of(), null, "2-1");
        String text = TenderResultsComposer.digest(List.of(done, contract), "https://ais.example").get(0).text();
        assertThat(text).startsWith("📊 Итоги по нашим тендерам: 2")
                .contains("✓ Тележка — ГКП «ZZ»: победил ИП X")
                .contains("📄 Шапочка — ГКП «YY»: договор с ТОО «Поставщик»")
                .contains("\n   https://ais.example/tenders?openId=101&market=KZ");
    }
}
