package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ProtocolStatus;
import com.vladoose.nir.entity.TenderLotResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Тексты итогов (спека 2026-10-22-tender-results-market-prices §7, §11). Пробелы в числах — неразрывные. */
class ResultTextsTest {

    private static final String S = "\u00A0";

    @Test
    void money() {
        assertThat(ResultTexts.money(new BigDecimal("251000"))).isEqualTo("251" + S + "000" + S + "₸");
        assertThat(ResultTexts.money(new BigDecimal("4500.5"))).isEqualTo("4" + S + "500,50" + S + "₸");
        assertThat(ResultTexts.money(new BigDecimal("3700000"))).isEqualTo("3,7" + S + "млн" + S + "₸");
        assertThat(ResultTexts.money(new BigDecimal("1340000"))).isEqualTo("1,34" + S + "млн" + S + "₸");
        assertThat(ResultTexts.money(null)).isEqualTo("—");
    }

    @Test
    void range() {
        assertThat(ResultTexts.range(new BigDecimal("251000"), new BigDecimal("613500"))).isEqualTo("251–613" + S + "тыс.");
        assertThat(ResultTexts.range(new BigDecimal("1200000"), new BigDecimal("3400000"))).isEqualTo("1,2–3,4" + S + "млн");
        assertThat(ResultTexts.range(new BigDecimal("900"), new BigDecimal("950"))).isEqualTo("900–950" + S + "₸");
        assertThat(ResultTexts.range(new BigDecimal("900"), new BigDecimal("900"))).isEqualTo("900" + S + "₸");
    }

    @Test
    void unitMethodBids() {
        assertThat(ResultTexts.unit("Штука", null)).isEqualTo("шт");
        assertThat(ResultTexts.unit("Упаковка", null)).isEqualTo("уп");
        assertThat(ResultTexts.unit(null, "компл")).isEqualTo("компл");
        assertThat(ResultTexts.unit(null, null)).isEqualTo("шт");
        assertThat(ResultTexts.method("88208881-ЗЦП2")).isEqualTo("ЗЦП");
        assertThat(ResultTexts.method("88208881-ОИ3")).isEqualTo("ОИ");
        assertThat(ResultTexts.method("12345")).isNull();
        assertThat(ResultTexts.methodName("ОК")).isEqualTo("конкурс");
        assertThat(ResultTexts.bids(1)).isEqualTo("1 заявка");
        assertThat(ResultTexts.bids(3)).isEqualTo("3 заявки");
        assertThat(ResultTexts.bids(10)).isEqualTo("10 заявок");
        assertThat(ResultTexts.bids(21)).isEqualTo("21 заявка");
        assertThat(ResultTexts.bids(12)).isEqualTo("12 заявок");
    }

    @Test
    void eventTexts() {
        TenderLotResult r = TenderLotResult.builder().winnerName("ИП Әбдібай").winnerBin("900000000001").winnerPrice(new BigDecimal("251000"))
                .bidsCount(10).minPrice(new BigDecimal("251000")).maxPrice(new BigDecimal("613500")).build();
        assertThat(ResultTexts.done(r, "шт", new BigDecimal("613900")))
                .isEqualTo("победил ИП Әбдібай — 251" + S + "000" + S + "₸ за шт · 10 заявок (251–613" + S + "тыс.) · бюджет 613" + S + "900" + S + "₸");
        assertThat(ResultTexts.done(new TenderLotResult(), "шт", null)).isEqualTo("состоялся");
        assertThat(ResultTexts.oneBid("ИП БОЛАТ", new BigDecimal("3700000"), "шт"))
                .isEqualTo("1 заявка → из одного источника у ИП БОЛАТ, 3,7" + S + "млн" + S + "₸ за шт");
        assertThat(ResultTexts.failedOther("Признать … по лоту № 1-ЗЦП1 несостоявшейся в связи с отклонением всех заявок",
                ProtocolStatus.PARSED)).isEqualTo("не состоялся (отклонением всех заявок) — объявят заново");
        assertThat(ResultTexts.failedOther(null, ProtocolStatus.UNKNOWN_FORMAT)).isEqualTo("не состоялся (протокол не разобран) — объявят заново");
        assertThat(ResultTexts.contract("ТОО «Поставщик»", new BigDecimal("43000"), "шт", "070740006294/260209/00", LocalDate.of(2026, 10, 20)))
                .isEqualTo("договор с ТОО «Поставщик» — 43" + S + "000" + S + "₸ за шт, № 070740006294/260209/00 от 20.10.2026");
        assertThat(ResultTexts.repeat("17790001-1", "запрос цен", LocalDateTime.of(2026, 10, 30, 10, 0)))
                .isEqualTo("объявлена повторная попытка № 17790001-1 (запрос цен), приём до 30.10 10:00");
        assertThat(ResultTexts.oiFailed("ИП Айя")).isEqualTo("из одного источника сорвалось (ИП Айя не подписал) — объявят заново");
        assertThat(ResultTexts.oiDone(null)).isEqualTo("из одного источника — состоялся");
    }

    @Test
    void ourBid() {
        assertThat(ResultTexts.ourBid(true, 1, 10, new BigDecimal("251000"), null, null, false, null, "шт"))
                .isEqualTo("🏆 Выиграли: наша цена 251" + S + "000" + S + "₸ за шт");
        assertThat(ResultTexts.ourBid(false, 2, 4, new BigDecimal("260000"), "ИП X", new BigDecimal("247000"), false, null, "шт"))
                .isEqualTo("📉 Наше место 2 из 4: наша цена 260" + S + "000" + S + "₸ за шт, победил ИП X — 247" + S + "000" + S + "₸ (−5" + S + "%)");
        assertThat(ResultTexts.ourBid(false, null, 4, null, null, null, true, "не соответствует ТЗ", "шт"))
                .isEqualTo("⛔ Наша заявка отклонена: не соответствует ТЗ");
        assertThat(ResultTexts.ourBid(false, 1, 1, new BigDecimal("100"), null, null, false, null, "шт"))
                .isEqualTo("📋 Наша заявка: место 1 из 1, наша цена 100" + S + "₸ за шт");
    }

    /** Живая проверка 2026-10-22: «победил Товарищество с ограниченной ответственностью "…"» — форма собственности коротко. */
    @Test
    void who_shortLegalForm() {
        assertThat(ResultTexts.who("Товарищество с ограниченной ответственностью \"Братья\"", "190140008213")).isEqualTo("ТОО \"Братья\"");
        assertThat(ResultTexts.who("Индивидуальный предприниматель Айя", null)).isEqualTo("ИП Айя");
        assertThat(ResultTexts.who("АКЦИОНЕРНОЕ ОБЩЕСТВО «Медтехника»", null)).isEqualTo("АО «Медтехника»");
        assertThat(ResultTexts.who("ИП Әбдібай", null)).isEqualTo("ИП Әбдібай");
        assertThat(ResultTexts.who(null, "190140008213")).isEqualTo("БИН 190140008213");
    }
}
