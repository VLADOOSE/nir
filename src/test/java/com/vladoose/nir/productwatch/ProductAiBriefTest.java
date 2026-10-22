package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    @Autowired LotModelService lotModel;
    @Autowired MedEquipmentRepository equipment;
    @Autowired MedRegistryRepository registry;
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
        omh = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
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

    /** Наш товар-кандидат — его код [P…] в тексте для ИИ (спека 2026-10-21-tender-telegram-bot §5). */
    SiteProduct omh;

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
                .contains("--- Лот № 170000011-ЗЦП1 [L" + t.getLots().get(0).getId() + "]: Аспиратор — 2 шт., до 900")
                .contains("Сверка АИС: ✓ подходит наш товар: Отсасыватель")
                .contains("Требования ТЗ (")
                .contains("производительность отсасывания")
                .contains("Наши товары-кандидаты:")
                .contains("* [P" + omh.getId() + "] Отсасыватель медицинский хирургический ОМХ-5/80")
                .contains("/product/omh")
                .contains("РУ РК-1 — действует бессрочно")
                .contains("Характеристики (сайт): Производительность по воздуху: не менее 15 л/мин")
                .contains("Из карточки РУ: назначение — Удаление жидкостей из полостей")
                .contains("очень длинное требование ".repeat(300));            // у одного тендера ТЗ — целиком
    }

    /**
     * Похожие наши товары отметки — с кодами [P…], короткой строкой без характеристик (текст не разрастается): ИИ может
     * назвать их кодом (прод 2026-10-22: L8375 — «нейтральные электроды ФОТЕК, кода P в выгрузке нет»).
     */
    @Test
    void similarProducts_listedWithCodes_withoutDuplicates() {
        Tender t = ourTender("17000009-1", ProductFitEvaluateTest.ASPIRATOR_SPEC, 1);
        SiteProduct kit = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Набор канюль для отсасывателя").brand("Аксион").slug("kanyuli").registrationCert("РК-1")
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Длина канюли", "250 мм"))))
                .syncedAt(OffsetDateTime.now()).build());
        TenderLotProductMatch m = matches.findByLotId(t.getLots().get(0).getId()).get(0);
        m.setSimilar(new ArrayList<>(List.of(
                new SimilarProductRef(omh.getId(), omh.getName(), "Аксион", "omh", 0.9),   // уже кандидат — второй раз не печатается
                new SimilarProductRef(kit.getId(), kit.getName(), "Аксион", "kanyuli", 0.5))));
        matches.save(m);
        em.flush();
        String text = brief.forTender(t.getId());
        assertThat(text).contains("у наших товаров-кандидатов и похожих наших товаров этого лота [P…]")
                .contains("Похожие наши товары")
                .contains("* [P" + kit.getId() + "] Набор канюль для отсасывателя")
                .contains("/product/kanyuli")
                .doesNotContain("Длина канюли");                                         // без характеристик
        assertThat(text.split("\\[P" + omh.getId() + "]", -1)).hasSize(2);              // ОМХ — один раз, среди кандидатов
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

    /** «Модель в работе» (спека 2026-10-20-lot-model-in-work §7): строка, заметка и сверка; лот без отметки, но с моделью — тоже. */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void modelInWork_lineNoteAndFit_evenWithoutOurMark() {
        Tender t = ourTender("17000777-1", ProductFitEvaluateTest.ASPIRATOR_SPEC, 1);
        TenderLot first = t.getLots().get(0);
        MedEquipment eq = equipment.save(MedEquipment.builder().name("ZZ Отсасыватель").manufact("ZZ Завод").market(Market.KZ)
                .spec("Производительность по воздуху не менее 20 л/мин").build());
        lotModel.apply(first, eq, "ИИ: подходит по 3 из 3");
        TenderLot other = new TenderLot();
        other.setTender(t);
        other.setLotNumber(2);
        other.setEquipName("ZZ Шкаф");
        t.getLots().add(other);
        em.persist(other);                       // не tenders.save(t): merge положил бы в коллекцию копию, а `other` остался бы новым
        em.flush();
        lotModel.apply(other, equipment.save(MedEquipment.builder().name("ZZ Шкаф медицинский").manufact("не указан")
                .market(Market.KZ).build()), null);
        em.flush();
        String text = brief.forTender(t.getId());
        assertThat(text).contains("Если у лота указана «Модель в работе» — проверь её первой.")
                .contains("Модель в работе: ZZ Отсасыватель (ZZ Завод), РУ не указано; закрепил vlad")
                .contains("почему: ИИ: подходит по 3 из 3")
                .contains("Сверка АИС модели в работе: ✓ ")
                .contains("--- Лот № 2 [L" + other.getId() + "]: ZZ Шкаф")
                .contains("Модель в работе: ZZ Шкаф медицинский, РУ не указано; закрепил vlad");
    }

    /** РУ модели в работе — номер и статус (спека 2026-10-20-lot-model-in-work §7): ИИ первым проверит регистрацию. */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void modelInWork_ruNumberWithStatus() {
        Tender t = ourTender("17000778-1", ProductFitEvaluateTest.ASPIRATOR_SPEC, 1);
        MedRegistry reg = registry.save(MedRegistry.builder().regNumber("ZZ-РУ-AI-1").name("ZZ Аспиратор старый").producer("ZZ")
                .expirationDate(LocalDate.of(2020, 2, 1)).unlimited(false).detailFetchedAt(OffsetDateTime.now()).build());
        lotModel.apply(t.getLots().get(0), lotModel.equipmentForRegistry(reg), null);
        em.flush();
        assertThat(brief.forTender(t.getId()))
                .contains("Модель в работе: ZZ Аспиратор старый (ZZ), РУ ZZ-РУ-AI-1 — истекло 01.02.2020; закрепил vlad");
    }
}
