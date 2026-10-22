package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Отправка итогов (спека 2026-10-22-tender-results-market-prices §7): темы и звук, повтор после сбоя, старьё — молча. */
@SpringBootTest
@Transactional
class TenderResultsNotifierTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final OffsetDateTime T0 = OffsetDateTime.now(CLOCK);
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:9", "dev:token", "-1001", "", "55", "95");

    record Sent(ResultTopic topic, String text, boolean silent) {}

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired TenderResultEventRepository events;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired ProductMatchQuery products;
    @Autowired PlatformTransactionManager tm;

    final List<Sent> sent = new ArrayList<>();
    RuntimeException failNext;
    TenderResultsNotifier notifier;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        events.deleteAllInBatch();
        notifier = notifier(true);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private TenderResultsNotifier notifier(boolean enabled) {
        return new TenderResultsNotifier(events, tenders, lots, results, subjectRepo, products, TG, (topic, text, silent) -> {
            if (failNext != null) {
                RuntimeException e = failNext;
                failNext = null;
                throw e;
            }
            sent.add(new Sent(topic, text, silent));
        }, tm, enabled, "https://ais.example", CLOCK);
    }

    private Tender tender(long buyId) {
        Tender t = ResultsFixtures.tender(tenders, buyId, NOW.minusDays(2), "88208881-ЗЦП2", "Тележка медицинская");
        subjectRepo.save(GoszakupSubject.builder().bin("110240019426").nameRu("ГКП «ZZ больница»").phone("8 (71133) 31398")
                .email("zz@mail.kz").fetchedAt(T0).build());
        return t;
    }

    private TenderResultEvent event(Tender t, ResultEventKind kind, ResultTopic topic, String text, OffsetDateTime created) {
        return events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(t.getLots().get(0).getId()).kind(kind)
                .text(text).urgent(kind.urgent()).topic(topic).createdAt(created)
                .notifiedAt(kind.urgent() ? null : created).digestedAt(kind.urgent() || topic != ResultTopic.PRODUCTS ? created : null)
                .build());
    }

    @Test
    void urgent_twoTopics_soundOnlyForOurs() {
        Tender t = tender(970003001L);
        TenderResultEvent call = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0);
        TenderResultEvent bid = event(t, ResultEventKind.OUR_BID, ResultTopic.PRODUCTS, "🏆 Выиграли: наша цена 1 ₸ за шт", T0);
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent).extracting(Sent::topic).containsExactly(ResultTopic.PRODUCTS, ResultTopic.TENDERS);
        Sent products = sent.get(0), tendersMsg = sent.get(1);
        assertThat(products.silent()).as("наши — со звуком").isFalse();
        assertThat(products.text()).startsWith("🏁 Наша заявка в протоколе: 1").contains("🏆 Выиграли");
        assertThat(tendersMsg.silent()).as("чужие ЗКО — тихо").isTrue();
        assertThat(tendersMsg.text()).startsWith("📞 Звонить: не состоялось 1").contains("☎ 8 (71133) 31398 · zz@mail.kz")
                .contains("goszakup № 970003001-1").contains("https://ais.example/tenders?openId=" + t.getId() + "&market=KZ");
        assertThat(events.findById(call.getId()).orElseThrow().getNotifiedAt()).isNotNull();
        assertThat(events.findById(bid.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    /** Telegram не принял — событие ждёт; следующий проход шлёт один раз (Review Focus 3). */
    @Test
    void failedSend_retriedNextPass_once() {
        Tender t = tender(970003002L);
        TenderResultEvent call = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0);
        failNext = new IllegalStateException("Telegram: HTTP 500");
        ImportSummary sum = new ImportSummary();
        notifier.flushUrgent(sum);
        assertThat(sent).isEmpty();
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(events.findById(call.getId()).orElseThrow().getNotifiedAt()).isNull();
        notifier.flushUrgent(new ImportSummary());
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent).hasSize(1);
    }

    @Test
    void stale_markedWithoutSending() {
        Tender t = tender(970003003L);
        TenderResultEvent old = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0.minusDays(4));
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent).isEmpty();
        assertThat(events.findById(old.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    @Test
    void digest_quiet_productsOnly() {
        Tender t = tender(970003004L);
        TenderResultEvent done = event(t, ResultEventKind.DONE, ResultTopic.PRODUCTS, "победил ИП X — 1 ₸ за шт", T0);
        TenderResultEvent contract = event(t, ResultEventKind.CONTRACT, ResultTopic.PRODUCTS, "договор с ТОО «Поставщик»", T0);
        event(t, ResultEventKind.DONE, ResultTopic.NONE, "чужой итог", T0);
        notifier.digest(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).silent()).isTrue();
        assertThat(sent.get(0).topic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(sent.get(0).text()).startsWith("📊 Итоги по нашим тендерам: 2").doesNotContain("чужой итог");
        assertThat(events.findById(done.getId()).orElseThrow().getDigestedAt()).isNotNull();
        assertThat(events.findById(contract.getId()).orElseThrow().getDigestedAt()).isNotNull();
    }

    @Test
    void chainInCallBlock() {
        Tender t = tender(970003005L);
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId()).outcome(LotOutcome.FAILED)
                .attempts(List.of(new ResultAttempt("970003005-1", "88208881-ЗЦП2", "ЗЦП", 370, "Закупка не состоялась", null, null, null, null),
                        new ResultAttempt("970003006-1", "88208881-ОИ3", "ОИ", 370, "Закупка не состоялась", null, null, "900000000001", null)))
                .build());
        event(t, ResultEventKind.OI_FAILED, ResultTopic.TENDERS, "из одного источника сорвалось (Поставщик 1 не подписал) — объявят заново", T0);
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent.get(0).text()).contains("Попытки: ЗЦП2 — не состоялся → ОИ3 — сорвался");
    }

    @Test
    void disabled_noSend() {
        Tender t = tender(970003007L);
        TenderResultEvent call = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0);
        notifier(false).flushUrgent(new ImportSummary());
        assertThat(sent).isEmpty();
        assertThat(events.findById(call.getId()).orElseThrow().getNotifiedAt()).isNull();
    }
}
