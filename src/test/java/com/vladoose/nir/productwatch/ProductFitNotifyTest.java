package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** «Только ★» (решение оператора 2026-10-20) включает «✓ подходит»; «подходит по ТЗ, но РУ истекло» — не шлём. */
@SpringBootTest
@Transactional
class ProductFitNotifyTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductMatchQuery query;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;

    record Sent(String text, boolean silent) {}
    final List<Sent> sent = new ArrayList<>();
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:1", "t:k", "-1", "52", "55");
    List<ProductGroup> groups;
    SiteProduct omh;
    ProductMatchService matchService;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        // признаки — характеристикой сайта, не в названии: фразы названий — правило «ТЗ списано» (COPIED поверх FITS)
        omh = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80 «Аксион»")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа"),
                        new SiteProductAttribute("Особенности", "работа от электрической сети, вакуумная система, ёмкость для сбора жидкости"))))
                .build());
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Снимок с РУ ОМХ в этом статусе. */
    void snapshot(RegStatus status) {
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh),
                Map.of("РК-1", new RegistrationInfo("РК-1", status, LocalDate.of(2026, 6, 28), false, null, null, null, List.of())), Set.of());
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    /** Тендер с одним лотом: цена за единицу × количество, разобранное ТЗ, приём до 22.10.2026 13:00; отметки посчитаны. */
    Tender tender(String ext, String lotName, String spec, int qty, String unitPrice) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп медицинских изделий"); t.setPlatform(TenderPlatform.GOSZAKUP);
        LocalDateTime deadlineAt = LocalDateTime.of(2026, 10, 22, 13, 0);
        t.setDeadline(deadlineAt.toLocalDate()); t.setDeadlineAt(deadlineAt);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName); l.setQuantity(qty);
        l.setMaxCost(new BigDecimal(unitPrice)); l.setRequiredSpec(spec); l.setTechSpecStatus(TechSpecStatus.OK);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        return t;
    }

    ProductWatchNotifier notifier() {
        return new ProductWatchNotifier(matches, tenders, query, matchService, tm, TG, (text, silent) -> sent.add(new Sent(text, silent)),
                true, ProductNotifyPolicy.of("star", "1000000"), "https://ais.westmed.kz",
                Clock.fixed(Instant.parse("2026-10-21T05:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void fits_sentInStarMode_withSound() {
        snapshot(RegStatus.ACTIVE);
        Tender t = tender("FN-1", "Аспиратор", ProductFitEvaluateTest.ASPIRATOR_SPEC, 2, "900000");   // наши лоты — 1,8 млн
        notifier().afterRun(new ImportSummary());
        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.text()).contains("✅ Подходит наш товар").contains("№ FN-1");
            assertThat(s.silent()).isFalse();
        });
        em.flush();
        em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.FITS);
    }

    @Test
    void fitsNoRu_notSent() {
        snapshot(RegStatus.EXPIRED);
        tender("FN-2", "Аспиратор", ProductFitEvaluateTest.ASPIRATOR_SPEC, 2, "900000");
        notifier().afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
    }

    @Test
    void fits_belowThreshold_notSent() {
        snapshot(RegStatus.ACTIVE);
        tender("FN-3", "Аспиратор", ProductFitEvaluateTest.ASPIRATOR_SPEC, 1, "900000");          // 900 тыс. — меньше 1 млн
        notifier().afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
    }
}
