package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Сверка в отметках (спека §6.5): FITS поднимает TYPE, NOT_FIT и «ТЗ под X» остаются у TYPE, бренд-группа — без сверки. */
@SpringBootTest
@Transactional
class ProductFitEvaluateTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductMatchQuery query;
    @Autowired EntityManager em;

    ProductMatchService service;
    SiteProduct omh, ehvch;

    static final String ASPIRATOR_SPEC = "Приложение 2\nТехническая спецификация\nОписание и требуемые функциональные, технические, "
            + "качественные и эксплуатационные характеристики закупаемых товаров:\nМедицинский аспиратор. Основные технические "
            + "характеристики: работа от электрической сети; вакуумная система отсасывания; производительность отсасывания: "
            + "примерно 15–30 л/мин; уровень вакуума: до 0–75 кПа; наличие ёмкости для сбора отсасываемой жидкости.";

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        // группы сида (V36–V38): в них есть «Электрохирургия (Фотек)» — в ProductFixtures.groups() её нет
        List<ProductGroup> groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        // признаки — характеристикой сайта, не в названии: фразы названий — правило «ТЗ списано» (COPIED поверх FITS)
        omh = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80 «Аксион»")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа"),
                        new SiteProductAttribute("Особенности", "работа от электрической сети, вакуумная система, ёмкость для сбора жидкости"))))
                .build());
        ehvch = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("ВЧ электрохирургический блок для аппарата ЭХВЧ-80-03-«ФОТЕК»").brand("Фотек").slug("ehvch80")
                .registrationCert("РК-2").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Максимальная выходная мощность", "80 Вт")))).build());
        Map<String, RegistrationInfo> regs = Map.of(
                "РК-1", new RegistrationInfo("РК-1", RegStatus.ACTIVE, null, true, null, null, null, List.of()),
                "РК-2", new RegistrationInfo("РК-2", RegStatus.ACTIVE, null, true, null, null, null, List.of()));
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh, ehvch), regs, Set.of());
        service = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    Tender tender(String ext, String lotName, String spec, TechSpecStatus status) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп медицинских изделий"); t.setPlatform(TenderPlatform.GOSZAKUP);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName); l.setRequiredSpec(spec); l.setTechSpecStatus(status);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        return t;
    }

    /** Строка отметки группы вида (не «Наши бренды и модели»). */
    TenderLotProductMatch row(Tender t) {
        return matches.findByTenderId(t.getId()).stream()
                .filter(m -> groupRepo.findById(m.getGroupId()).map(g -> !g.isBrandGroup()).orElse(false))
                .findFirst().orElseThrow();
    }

    @Test
    void parsedSpec_fits_levelRaised() {
        Tender t = tender("FIT-1", "Аспиратор", ASPIRATOR_SPEC, TechSpecStatus.OK);
        ProductMatchService.Result r = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r.level()).isEqualTo(ProductMatchLevel.FITS);
        TenderLotProductMatch m = row(t);
        assertThat(m.getFit()).isEqualTo(FitVerdict.FITS);
        assertThat(m.getFitProductId()).isEqualTo(omh.getId());
        assertThat(m.getFitMet()).isGreaterThanOrEqualTo(3);
        assertThat(m.getFitCandidates()).isNotEmpty();
        assertThat(m.getFitCandidates().get(0).rows()).isNotEmpty();
        assertThat(m.getFitCheckedAt()).isNotNull();
        assertThat(query.ourProduct(List.of(t.getId())).get(t.getId()).label()).startsWith("✓ подходит: Отсасыватель");
        // для сортировки списка «Ближе к нашему товару» — вердикт и цифры лучшей сверки
        assertThat(query.ourProduct(List.of(t.getId())).get(t.getId()).fit().verdict()).isEqualTo("FITS");
        assertThat(query.ourProduct(List.of(t.getId())).get(t.getId()).fit().met()).isEqualTo(m.getFitMet());
    }

    /** Review Focus #5: ТЗ не разобрано, но в описании площадки есть числа — сверка по описанию. */
    @Test
    void descriptionOnlyLot_fitComputed() {
        Tender t = tender("FIT-2", "Отсасыватель", "Отсасыватель медицинский: работа от электрической сети; производительность "
                + "не менее 15 л/мин; разрежение до 75 кПа; вакуумная система отсасывания", null);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        TenderLotProductMatch m = row(t);
        assertThat(m.getFit()).isNotNull().isNotEqualTo(FitVerdict.NO_REQUIREMENTS);
    }

    /** ТЗ под BOWA с мощностью больше нашей — «не подходит», «ТЗ под BOWA», уровень TYPE. */
    @Test
    void foreignAndNotFit_staysType() {
        Tender t = tender("FIT-3", "Электрокоагулятор", "Подробные технические характеристики основного блока ЭХВЧ BOWA ARC 303; "
                + "выходная мощность не менее 300 Вт; режим биполярной коагуляции; режим монополярного резания", TechSpecStatus.OK);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        TenderLotProductMatch m = row(t);
        assertThat(m.getLevel()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.getFit()).isEqualTo(FitVerdict.NOT_FIT);
        assertThat(m.getForeignBrand()).isEqualTo("BOWA");
        assertThat(m.getFitReason()).contains("300");
        assertThat(query.ourProduct(List.of(t.getId())).get(t.getId()).label()).startsWith("✗ не подходит: ");
    }

    /** Без ТЗ и без чисел — требований нет, сверки нет, уровень TYPE. */
    @Test
    void noRequirements_type() {
        Tender t = tender("FIT-4", "Отсасыватель", "медицинский", null);
        ProductMatchService.Result r = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r.level()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(row(t).getFit()).isEqualTo(FitVerdict.NO_REQUIREMENTS);
    }

    /** «Наши бренды и модели» — назван наш товар, сверка не нужна: у её строки fit пуст. */
    @Test
    void brandGroup_noFit() {
        Tender t = tender("FIT-6", "Аппарат для аспирации дыма АСД-«ФОТЕК»", ASPIRATOR_SPEC, TechSpecStatus.OK);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        TenderLotProductMatch brand = matches.findByTenderId(t.getId()).stream()
                .filter(m -> groupRepo.findById(m.getGroupId()).map(ProductGroup::isBrandGroup).orElse(false))
                .findFirst().orElseThrow();
        assertThat(brand.getLevel()).isEqualTo(ProductMatchLevel.NAMED);
        assertThat(brand.getFit()).isNull();
        assertThat(brand.getFitCandidates()).isEmpty();
    }

    /** FITS ещё не сообщён — в очереди уведомлений; после отметки «сообщено FITS» — нет. */
    @Test
    void notifyQueueKnowsFits() {
        Tender t = tender("FIT-5", "Аспиратор", ASPIRATOR_SPEC, TechSpecStatus.OK);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(matches.findTendersToNotify()).contains(t.getId());
        service.markNotified(t.getId(), ProductMatchLevel.TYPE);   // сообщён как «наш вид» — «подходит» выше, снова в очереди
        em.flush();
        assertThat(matches.findTendersToNotify()).contains(t.getId());
        service.markNotified(t.getId(), ProductMatchLevel.FITS);
        em.flush();
        assertThat(matches.findTendersToNotify()).doesNotContain(t.getId());
    }
}
