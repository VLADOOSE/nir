package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Сверка у лота и вкладка «Товары» через API (спека §11). */
@SpringBootTest
@Transactional
class ProductCatalogApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired SiteProductRepository products;
    @Autowired ProductRegistrationRepository registrations;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired EntityManager em;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        products.deleteAllInBatch();
        registrations.deleteAllInBatch();
        em.flush();
    }

    @AfterEach
    void clear() {
        MarketContext.clear();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void catalogList_regStatusAndAttributes() throws Exception {
        products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("ОМХ-5/80").brand("Аксион").slug("omh")
                .registrationCert("РК-МТ-5№022493").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность", "15 л/мин")))).build());
        products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("Педаль").brand("Фотек").slug("pedal")
                .syncedAt(OffsetDateTime.now()).build());
        registrations.save(ProductRegistration.builder().regNumber("РК-МТ-5№022493").status(RegStatus.EXPIRED)
                .expirationDate(LocalDate.of(2026, 6, 28)).unlimited(false).checkedAt(OffsetDateTime.now()).build());
        em.flush();
        mvc.perform(get("/api/product-catalog").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kz").value(true))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.name == 'ОМХ-5/80')].regStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.items[?(@.name == 'ОМХ-5/80')].regExpires").value("2026-06-28"))
                .andExpect(jsonPath("$.items[?(@.name == 'ОМХ-5/80')].attributesCount").value(1))
                .andExpect(jsonPath("$.items[?(@.name == 'Педаль')].regStatus").value("NOT_SPECIFIED"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rfMarket_empty() throws Exception {
        mvc.perform(get("/api/product-catalog").header("X-Market", "RF"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kz").value(false)).andExpect(jsonPath("$.items.length()").value(0));
    }

    /**
     * Лот с отметкой и разобранным ТЗ: вердикт, кандидат со строками и ссылкой, «Требования ТЗ»;
     * лот без разобранного ТЗ (описание площадки с числами) — без «Требований ТЗ» (список — только у разобранного ТЗ).
     */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void lotsCarryFitAndRequirements() throws Exception {
        List<ProductGroup> groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        SiteProduct omh = products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80 от электрической сети, вакуумная система, ёмкость для сбора жидкости")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа")))).build());
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh),
                Map.of("РК-1", new RegistrationInfo("РК-1", RegStatus.ACTIVE, null, true, null, null, null, List.of())), Set.of());
        Tender t = new Tender();
        t.setTenderNumber("API-1"); t.setSourceExtId("API-1"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп"); t.setPlatform(TenderPlatform.GOSZAKUP); t.setSource(Source.PUBLIC_TENDER);
        TenderLot asp = new TenderLot(); asp.setTender(t); asp.setLotNumber(1); asp.setEquipName("Аспиратор");
        asp.setRequiredSpec(ProductFitEvaluateTest.ASPIRATOR_SPEC); asp.setTechSpecStatus(TechSpecStatus.OK);
        TenderLot couch = new TenderLot(); couch.setTender(t); couch.setLotNumber(2); couch.setEquipName("Кушетка");
        couch.setRequiredSpec("Кушетка смотровая: длина не менее 1900 мм; ширина не менее 600 мм; нагрузка не менее 150 кг.");
        t.getLots().add(asp);
        t.getLots().add(couch);
        tenders.save(t);
        em.flush();
        new ProductMatchService(tenders, matches, () -> snap).evaluate(t.getId(), ProductMatchSource.SEARCH);
        em.flush();
        String json = mvc.perform(get("/api/tenders/" + t.getId() + "/lots").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> lots = new ObjectMapper().readValue(json, new TypeReference<>() {});
        Map<String, Object> a = lots.stream().filter(l -> "Аспиратор".equals(l.get("equipName"))).findFirst().orElseThrow();
        Map<String, Object> c = lots.stream().filter(l -> "Кушетка".equals(l.get("equipName"))).findFirst().orElseThrow();
        assertThat((List<?>) a.get("requirements")).hasSizeGreaterThan(2);
        assertThat((List<?>) c.get("requirements")).as("описание площадки — не разобранное ТЗ").isEmpty();
        Map<?, ?> m = (Map<?, ?>) ((List<?>) a.get("productMatches")).get(0);
        assertThat(m.get("fit")).isEqualTo("FITS");
        Map<?, ?> best = (Map<?, ?>) ((List<?>) m.get("candidates")).get(0);
        assertThat((String) best.get("name")).startsWith("Отсасыватель");
        assertThat((String) best.get("url")).endsWith("/product/omh");
        assertThat((List<?>) best.get("rows")).isNotEmpty();
        assertThat(((Map<?, ?>) ((List<?>) best.get("rows")).get(0)).get("mark")).isNotNull();
    }
}
