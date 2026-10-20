package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Текст «Для ИИ» (спека §9): запрос, тендер, наш лот, требования, наш товар с РУ; пределы; гард рынка. */
@SpringBootTest
@Transactional
class ProductAiBriefTest {

    /** Длинный хвост ТЗ: в общем списке режется до 4000 знаков на лот, у одного тендера — целиком. */
    static final String TAIL = "очень длинное требование ".repeat(400);

    @Autowired ProductAiBrief brief;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductRegistrationRepository registrations;
    @Autowired EntityManager em;
    ProductMatchService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        registrations.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        // признаки — характеристикой сайта, не в названии: фразы названий — правило «ТЗ списано» (COPIED поверх FITS)
        SiteProduct omh = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа"),
                        new SiteProductAttribute("Особенности", "работа от электрической сети, вакуумная система, ёмкость для сбора жидкости"))))
                .build());
        registrations.save(ProductRegistration.builder().regNumber("РК-1").status(RegStatus.ACTIVE).unlimited(true)
                .purpose("Удаление жидкостей из полостей").checkedAt(OffsetDateTime.now()).build());
        em.flush();
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh),
                Map.of("РК-1", new RegistrationInfo("РК-1", RegStatus.ACTIVE, null, true, null, null, null, List.of())), Set.of());
        service = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    /** Тендер KZ goszakup: `lots` лотов «Аспиратор» по 2 × 900 000 ₸ с разобранным ТЗ; отметки посчитаны. */
    Tender ourTender(String number, String spec, int lots) {
        Tender t = Tender.builder().tenderNumber(number).sourceExtId(number).status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.KZ).currency("KZT").platform(TenderPlatform.GOSZAKUP).description("Закуп медицинских изделий").build();
        for (int i = 1; i <= lots; i++) {
            TenderLot l = new TenderLot();
            l.setTender(t); l.setLotNumber(i); l.setSourceLotCode(number.replace("-", "") + "-ЗЦП" + i); l.setEquipName("Аспиратор");
            l.setQuantity(2); l.setMaxCost(new BigDecimal("900000")); l.setRequiredSpec(spec); l.setTechSpecStatus(TechSpecStatus.OK);
            t.getLots().add(l);
        }
        tenders.save(t);
        em.flush();
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        em.flush();
        return t;
    }

    @Test
    void singleTender_fullText_wholeSpec() {
        Tender t = ourTender("17000001-1", ProductFitEvaluateTest.ASPIRATOR_SPEC + " " + TAIL, 1);
        String text = brief.forTender(t.getId());
        assertThat(text).startsWith("Ты помогаешь компании West-Med")
                .contains("Тендер № … — Лот № … — Требуется: …")          // формат ответа из запроса
                .contains("=== Тендер № 17000001-1 (goszakup) — https://goszakup.gov.kz/ru/announce/index/17000001")
                .contains("--- Лот № 170000011-ЗЦП1: Аспиратор — 2 шт., до 900")
                .contains("Сверка АИС: ✓ подходит наш товар: Отсасыватель")
                .contains("Требования ТЗ (")
                .contains("производительность отсасывания")
                .contains("Наши товары-кандидаты:")
                .contains("* Отсасыватель медицинский хирургический ОМХ-5/80")
                .contains("/product/omh")
                .contains("РУ РК-1 — действует бессрочно")
                .contains("Характеристики (сайт): Производительность по воздуху: не менее 15 л/мин")
                .contains("Из карточки РУ: назначение — Удаление жидкостей из полостей")
                .contains("очень длинное требование ".repeat(300));            // у одного тендера ТЗ — целиком
    }

    @Test
    void list_capsAtFifteen_skipsOtherMarket_cutsLotSpec() {
        Tender rf = tenders.save(Tender.builder().tenderNumber("ZZ-AI-RF").status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.RF).currency("RUB").build());
        List<Long> ids = new ArrayList<>(List.of(rf.getId()));
        for (int i = 0; i < 16; i++) ids.add(ourTender("170001" + (10 + i) + "-1", ProductFitEvaluateTest.ASPIRATOR_SPEC + " " + TAIL, 1).getId());
        AiBriefResponse r = brief.forTenders(ids);
        assertThat(r.total()).isEqualTo(17);
        assertThat(r.tenders()).isEqualTo(ProductAiBrief.MAX_TENDERS);
        assertThat(r.text()).doesNotContain("ZZ-AI-RF").contains("17000110-1").contains("17000124-1").doesNotContain("17000125-1");
        assertThat(r.text()).as("ТЗ лота в общем списке — до 4000 знаков").doesNotContain("очень длинное требование ".repeat(200));
    }

    /** Весь текст — не длиннее 120 000 знаков, обрезка подписана. */
    @Test
    void list_totalCut() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) ids.add(ourTender("1700020" + i + "-1", ProductFitEvaluateTest.ASPIRATOR_SPEC + " " + TAIL, 6).getId());
        AiBriefResponse r = brief.forTenders(ids);
        assertThat(r.text().length()).isLessThanOrEqualTo(ProductAiBrief.MAX_TEXT);
        assertThat(r.text()).endsWith("…обрезано");
    }

    @Test
    void otherMarketOrPrivate_404() {
        Tender rf = tenders.save(Tender.builder().tenderNumber("ZZ-AI-RF2").status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.RF).currency("RUB").build());
        Tender pr = tenders.save(Tender.builder().tenderNumber("ZZ-AI-ЧЗ").status("NEW").source(Source.PRIVATE_REQUEST)
                .market(Market.KZ).currency("KZT").build());
        assertThatThrownBy(() -> brief.forTender(rf.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> brief.forTender(pr.getId())).isInstanceOf(NotFoundException.class);
    }
}
