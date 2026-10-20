package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Кому и как уходит «Наш товар в тендерах» (спека 2026-10-19-product-watch §9). */
@SpringBootTest
@Transactional
class ProductWatchNotifierTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductMatchQuery query;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;

    record Sent(String text, boolean silent) {}
    final List<Sent> sent = new ArrayList<>();
    boolean failSend;
    ProductMatchService matchService;
    CatalogSnapshot snap;
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:1", "t:k", "-1", "52", "55");

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        snap = CatalogSnapshot.build(groups, products);
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Всё, что отмечено, без порога суммы — поведение до решения оператора 2026-10-20 (на нём написаны тесты ниже). */
    private ProductWatchNotifier notifier(Clock clock) {
        return notifier(clock, ProductNotifyPolicy.of("all", "0"));
    }

    private ProductWatchNotifier notifier(Clock clock, ProductNotifyPolicy policy) {
        return new ProductWatchNotifier(matches, tenders, query, matchService, tm, TG,
                (text, silent) -> { if (failSend) throw new IllegalStateException("Telegram: HTTP 500"); sent.add(new Sent(text, silent)); },
                true, policy, "https://ais.westmed.kz", clock);
    }

    /** Тендер из нескольких лотов с ценой за единицу и количеством — для порога суммы наших лотов. */
    private Tender tenderWithLots(String ext, java.math.BigDecimal totalCost, TenderLot... lots) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп медицинских изделий"); t.setPlatform(TenderPlatform.GOSZAKUP); t.setTotalCost(totalCost);
        LocalDateTime deadlineAt = LocalDateTime.of(2026, 10, 20, 9, 0);
        t.setDeadline(deadlineAt.toLocalDate()); t.setDeadlineAt(deadlineAt);
        for (TenderLot l : lots) { l.setTender(t); t.getLots().add(l); }
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        return t;
    }

    private static TenderLot lot(String name, String spec, Integer qty, String unitPrice) {
        TenderLot l = new TenderLot();
        l.setEquipName(name);
        l.setQuantity(qty);
        if (unitPrice != null) l.setMaxCost(new java.math.BigDecimal(unitPrice));
        if (spec != null) { l.setRequiredSpec(spec); l.setTechSpecStatus(TechSpecStatus.OK); }
        return l;
    }

    static final ProductNotifyPolicy STAR_FROM_MILLION = ProductNotifyPolicy.of("star", "1000000");

    private Tender tender(String lotName, String spec, LocalDateTime deadlineAt) {
        return tender("PW-N", "Аппарат для аспирации дыма", lotName, spec, deadlineAt);
    }

    private Tender tender(String ext, String subject, String lotName, String spec, LocalDateTime deadlineAt) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription(subject); t.setPlatform(TenderPlatform.GOSZAKUP);
        t.setDeadline(deadlineAt.toLocalDate()); t.setDeadlineAt(deadlineAt);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName);
        if (spec != null) { l.setRequiredSpec(spec); l.setTechSpecStatus(TechSpecStatus.OK); }
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        return t;
    }

    static Clock at(String isoUtc) { return Clock.fixed(Instant.parse(isoUtc), ZoneOffset.UTC); }

    @Test
    void copied_soundOnce() {
        Tender t = tender("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), LocalDateTime.of(2026, 10, 20, 9, 0));
        ProductWatchNotifier n = notifier(at("2026-10-19T05:00:00Z"));
        n.afterRun(new ImportSummary());
        n.afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).silent()).isFalse();
        assertThat(sent.get(0).text()).contains("★ Точно наше").contains("ТЗ списано с нашего товара");
        em.flush(); em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.COPIED);
    }

    @Test
    void typeOnly_silent() {
        tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 20, 9, 0));
        notifier(at("2026-10-19T05:00:00Z")).afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).silent()).isTrue();
        assertThat(sent.get(0).text()).contains("🏷 Наш вид изделия");
    }

    @Test
    void deadlinePassedBeforeSend_notNotified() {   // Review Focus #3
        tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 19, 8, 45));
        notifier(at("2026-10-19T03:50:00Z")).afterRun(new ImportSummary());   // 08:50 по Уральску
        assertThat(sent).isEmpty();
    }

    @Test
    void sendFails_retryNextRun() {
        tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 20, 9, 0));
        failSend = true;
        ImportSummary sum = new ImportSummary();
        notifier(at("2026-10-19T05:00:00Z")).afterRun(sum);
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).contains("Telegram: HTTP 500");
        failSend = false;
        notifier(at("2026-10-19T05:30:00Z")).afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
    }

    /** Сообщено TYPE, потом ТЗ списано с нашего товара — короткое «★ Уточнение» со звуком, только наш точный лот. */
    @Test
    void upgradeAfterTypeNotified_shortWithSound() {
        Tender t = tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 20, 9, 0));
        notifier(at("2026-10-19T05:00:00Z")).afterRun(new ImportSummary());
        TenderLot lot = t.getLots().get(0);
        lot.setRequiredSpec(ProductFixtures.spec("17779323-1.pdf"));
        lot.setTechSpecStatus(TechSpecStatus.OK);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        notifier(at("2026-10-19T05:20:00Z")).afterRun(new ImportSummary());
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).text()).startsWith("★ Уточнение");
        assertThat(sent.get(1).silent()).isFalse();
    }

    /** Не поместившиеся в сообщение тендеры сообщёнными не считаются — уходят следующим прогоном (лучше повтор, чем потеря). */
    @Test
    void cutFromMessage_sentWithNextRun() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(tender("PW-N" + i, "Аппарат для аспирации дыма. Очень длинное наименование закупки для нужд больницы ".repeat(2),
                    "Отсасыватель", null, LocalDateTime.of(2026, 10, 20, 9, 0)).getId());
        }
        ProductWatchNotifier n = notifier(at("2026-10-19T05:00:00Z"));
        n.afterRun(new ImportSummary());
        assertThat(sent.get(0).text()).contains("…и ещё");
        int shown = sent.get(0).text().split("\n▸ ", -1).length - 1;
        em.flush();
        em.clear();
        long marked = ids.stream().filter(id -> tenders.findById(id).orElseThrow().getProductNotifiedLevel() != null).count();
        assertThat(marked).as("отмечены ровно показанные").isEqualTo(shown).isLessThan(ids.size());

        n.afterRun(new ImportSummary());
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).text()).startsWith("🏷 Наш товар в тендерах: " + (ids.size() - shown));
    }

    @Test
    void disabled_nothingSent() {
        tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 20, 9, 0));
        new ProductWatchNotifier(matches, tenders, query, matchService, tm, TG, (text, silent) -> sent.add(new Sent(text, silent)),
                false, ProductNotifyPolicy.of("all", "0"), "https://ais.westmed.kz", at("2026-10-19T05:00:00Z")).afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
    }

    // --- решение оператора 2026-10-20: в Telegram только «★», и только если наши лоты в тендере — от 1 млн ₸ ---

    /** «🏷 наш вид» в режиме «только ★» не шлётся и сообщённым не считается: станет ★ — придёт полной карточкой. */
    @Test
    void star_typeOnly_notSentNorMarked() {
        Tender t = tenderWithLots("PW-T", null, lot("Отсасыватель", null, 2, "900000"));
        notifier(at("2026-10-19T05:00:00Z"), STAR_FROM_MILLION).afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
        em.flush(); em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isNull();
    }

    @Test
    void star_exactFromMillion_sentWithSound_onlyExactTenders() {
        tenderWithLots("PW-T", null, lot("Отсасыватель", null, 2, "900000"));
        Tender c = tenderWithLots("PW-C", null, lot("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), 1, "1000000"));
        notifier(at("2026-10-19T05:00:00Z"), STAR_FROM_MILLION).afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).silent()).isFalse();
        assertThat(sent.get(0).text()).contains("№ PW-C").doesNotContain("№ PW-T");
        em.flush(); em.clear();
        assertThat(tenders.findById(c.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.COPIED);
    }

    /** «Только ★»: в карточке и в сумме — только точные лоты; дорогой «наш вид» рядом дешёвого «★» тендер не вытягивает. */
    @Test
    void star_cardAndSumOnlyExactLots() {
        tenderWithLots("PW-M", null,
                lot("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), 1, "1200000"),
                lot("Аспиратор хирургический", null, 2, "900000"));
        tenderWithLots("PW-N", null,
                lot("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), 1, "300000"),
                lot("Аспиратор хирургический", null, 2, "900000"));
        notifier(at("2026-10-19T05:00:00Z"), STAR_FROM_MILLION).afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).text()).contains("№ PW-M").contains("Отсасыватель").doesNotContain("Аспиратор")
                .doesNotContain("№ PW-N");
    }

    /** Порог — по НАШИМ лотам: дорогой чужой лот того же тендера в сумму не идёт. */
    @Test
    void cheapOurLots_notSent_evenWithExpensiveOtherLot() {
        Tender t = tenderWithLots("PW-C", new java.math.BigDecimal("5300000"),
                lot("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), 1, "300000"),
                lot("Бумага офисная", null, 100, "50000"));
        notifier(at("2026-10-19T05:00:00Z"), STAR_FROM_MILLION).afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
        em.flush(); em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isNull();
    }

    /** Цены у лота нет (площадка не дала количество), но лот в тендере один — сумма тендера и есть сумма лота. */
    @Test
    void singleLotWithoutPrice_tenderTotalCounts() {
        tenderWithLots("PW-C", new java.math.BigDecimal("2000000"),
                lot("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), null, null));
        notifier(at("2026-10-19T05:00:00Z"), STAR_FROM_MILLION).afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
    }

    @Test
    void off_nothingSent() {
        tenderWithLots("PW-C", null, lot("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), 1, "5000000"));
        notifier(at("2026-10-19T05:00:00Z"), ProductNotifyPolicy.of("off", "0")).afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
    }
}
