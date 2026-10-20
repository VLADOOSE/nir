package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import com.vladoose.nir.service.productwatch.ProcurementPlanDigest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Сводка планов раз в день (спека §10.3): первый сбор — молча, дальше — одно тихое сообщение о новом, ★ сверху. */
@SpringBootTest
@Transactional
class ProcurementPlanDigestTest {

    @Autowired ProcurementPlanPointRepository points;
    @Autowired PlatformTransactionManager tm;
    final List<String> sent = new ArrayList<>();
    final List<Boolean> silent = new ArrayList<>();
    ProcurementPlanDigest digest;

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T02:30:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        points.deleteAllInBatch();
        TelegramSettings tg = new TelegramSettings(true, "https://api.telegram.org", "1:x", "-1001", "");
        digest = new ProcurementPlanDigest(points, tm, tg, (text, quiet) -> { sent.add(text); silent.add(quiet); }, true,
                "https://ais.westmed.kz", CLOCK);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    ProcurementPlanPoint point(long extId, String name, ProductMatchLevel level, String amount) {
        OffsetDateTime t = OffsetDateTime.now(CLOCK);
        return points.save(ProcurementPlanPoint.builder().market(Market.KZ).extId(extId).year(2026).month(11).name(name)
                .customerName("ГКП «Айтекебийская районная больница»").region("Актюбинская область").quantity(new BigDecimal("4"))
                .unit("Штука").amount(new BigDecimal(amount)).level(level).firstSeenAt(t).lastSeenAt(t).build());
    }

    @Test
    void firstRunSilent_thenOneQuietMessageExactFirst() {
        point(1, "Рециркулятор", ProductMatchLevel.TYPE, "5920000");
        digest.flush(true, new ImportSummary());
        assertThat(sent).isEmpty();
        assertThat(points.findByExtId(1L).orElseThrow().getDigestedAt()).isNotNull();

        point(2, "Облучатель-рециркулятор", ProductMatchLevel.TYPE, "9000000");
        point(3, "Электрокардиограф ЭК12Т-01-«Р-Д»", ProductMatchLevel.NAMED, "1200000");
        digest.flush(false, new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(silent).containsExactly(true);
        String text = sent.get(0);
        assertThat(text).startsWith("🗓 Новое в планах закупок: 2");
        assertThat(text.indexOf("★ Электрокардиограф")).isLessThan(text.indexOf("Облучатель"));
        assertThat(text).contains("Облучатель-рециркулятор — 4 шт., 9\u00a0000\u00a0000,00 ₸ · ноябрь 2026");
        assertThat(text).contains("ноябрь 2026").contains("Актюбинская область")
                .contains("https://ais.westmed.kz/procurement-plans?fresh=1&market=KZ");

        digest.flush(false, new ImportSummary());
        assertThat(sent).as("повтор — тишина").hasSize(1);
    }

    @Test
    void sendFails_notDigested_retryNextTime() {
        point(5, "Рециркулятор", ProductMatchLevel.TYPE, "100");
        ProcurementPlanDigest failing = new ProcurementPlanDigest(points, tm,
                new TelegramSettings(true, "https://api.telegram.org", "1:x", "-1001", ""),
                (text, quiet) -> { throw new IllegalStateException("Telegram: HTTP 500"); }, true, "", CLOCK);
        ImportSummary sum = new ImportSummary();
        failing.flush(false, sum);
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(points.findByExtId(5L).orElseThrow().getDigestedAt()).isNull();
    }

    @Test
    void manyPoints_tenLinesAndRest() {
        for (int i = 0; i < 14; i++) point(100 + i, "Рециркулятор " + i, ProductMatchLevel.TYPE, String.valueOf(1000 + i));
        digest.flush(false, new ImportSummary());
        assertThat(sent.get(0).split("\n▸ ", -1)).hasSize(11);
        assertThat(sent.get(0)).contains("…и ещё 4");
    }
}
