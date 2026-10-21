package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.service.productwatch.ProductDigestComposer;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.productwatch.ProductWatchComposer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Сообщение-пачка (спека 2026-10-21-tender-telegram-bot §4). */
class ProductDigestComposerTest {

    static ProductWatchComposer.Card card(long id, ProductMatchLevel level, ProductMatchQuery.FitSummary fit, String customer,
                                          BigDecimal sum) {
        ProductMatchQuery.LotLine lot = new ProductMatchQuery.LotLine(id * 10, "Дефибриллятор", 1, level, "Дефибрилляторы",
                "дефибриллятор", null, null, null, List.of(), fit);
        return new ProductWatchComposer.Card(id, "177" + id + "-1", "goszakup", null, "Закуп", customer, "Алматы", null,
                LocalDate.of(2026, 11, 2), LocalDateTime.of(2026, 11, 2, 14, 59), level, false, List.of(lot), sum);
    }

    @Test
    void lineFormat_linkEscaped_sumDeadlineMark_sound() {
        var fit = new ProductMatchQuery.FitSummary(FitVerdict.FITS, "ДКИ-Н-12 «Аксион»", "dki", 21, 34, 13, null, null, "ACTIVE");
        var chunks = ProductDigestComposer.compose(List.of(card(533, ProductMatchLevel.FITS, fit, "ГКП <Горбольница №7> & К", new BigDecimal("12500000"))),
                "https://ais.westmed.kz/");
        assertThat(chunks).singleElement().satisfies(c -> {
            assertThat(c.html()).startsWith("🔎 Потенциально наших тендеров: 1");
            assertThat(c.html()).contains("1. ✅ <a href=\"https://ais.westmed.kz/tenders?openId=533&amp;market=KZ\">Дефибриллятор</a>")
                    .contains("ГКП &lt;Горбольница №7&gt; &amp; К, Алматы")
                    .contains("наши лоты 12,5 млн ₸ · до 02.11 14:59 · подходит наш ДКИ-Н-12 «Аксион» · 21 из 34");
            assertThat(c.sound()).isTrue();
            assertThat(c.tenderIds()).containsExactly(533L);
        });
    }

    @Test
    void typeIsSilent_iconsByFit_upgradePrefix() {
        var partial = new ProductMatchQuery.FitSummary(FitVerdict.PARTIAL, "ОБПЕ-450", "o", 5, 8, 3, null, null, "ACTIVE");
        var chunks = ProductDigestComposer.compose(List.of(card(1, ProductMatchLevel.TYPE, partial, "П2", new BigDecimal("1200000")),
                card(2, ProductMatchLevel.TYPE, null, "П3", null)), null);
        String html = chunks.get(0).html();
        assertThat(chunks.get(0).sound()).isFalse();
        assertThat(html).contains("1. ◐ Дефибриллятор — П2").contains("похоже: ОБПЕ-450 · 5 из 8")
                .contains("2. 🏷 Дефибриллятор — П3").contains("наш вид: дефибриллятор").doesNotContain("<a href");
    }

    @Test
    void orderStarFitsPartialType_keepsDeadlineOrderInside_upgradePrefix() {
        var partial = new ProductMatchQuery.FitSummary(FitVerdict.PARTIAL, "ОБПЕ-450", "o", 5, 8, 3, null, null, "ACTIVE");
        ProductWatchComposer.Card type1 = card(1, ProductMatchLevel.TYPE, null, "П1", null);
        ProductWatchComposer.Card part = card(2, ProductMatchLevel.TYPE, partial, "П2", null);
        ProductWatchComposer.Card type3 = card(3, ProductMatchLevel.TYPE, null, "П3", null);
        ProductWatchComposer.Card fits = card(4, ProductMatchLevel.FITS, null, "П4", null);
        ProductWatchComposer.Card c = card(5, ProductMatchLevel.NAMED, null, "П5", null);
        ProductWatchComposer.Card named = new ProductWatchComposer.Card(5, c.number(), c.platformLabel(), null, c.subject(),
                c.customer(), c.region(), null, c.deadline(), c.deadlineAt(), ProductMatchLevel.NAMED, true, c.lots(), null);
        var chunk = ProductDigestComposer.compose(List.of(type1, part, type3, fits, named), null).get(0);
        assertThat(chunk.tenderIds()).containsExactly(5L, 4L, 2L, 1L, 3L);   // ★, ✅, ◐, 🏷 — внутри порядок входа (срок)
        assertThat(chunk.html()).contains("1. ⬆ Уточнение: ★ ");
    }

    @Test
    void chunksOf15() {
        List<ProductWatchComposer.Card> cards = new ArrayList<>();
        for (long i = 1; i <= 16; i++) cards.add(card(i, ProductMatchLevel.TYPE, null, "Заказчик " + i, null));
        var chunks = ProductDigestComposer.compose(cards, null);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).tenderIds()).hasSize(15);
        assertThat(chunks.get(0).html()).startsWith("🔎 Потенциально наших тендеров: 16 (часть 1 из 2)");
        assertThat(chunks.get(1).tenderIds()).containsExactly(16L);
    }
}
