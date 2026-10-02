package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.service.CompanyImageKind;
import com.vladoose.nir.service.CompanyProfileService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import com.vladoose.nir.context.MarketContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * «Система → Реквизиты и печать»: права, рынок, проверка ставок, картинки (спека §7, §10, §11).
 * Реквизиты в nirdb — данные оператора: их правят живые проверки и сам оператор. Поэтому ожидаемое берётся из строки
 * рынка, а то, что тесту нужно (нет логотипа, у Регион-Мед нет печати, свой список ставок), ставится явно внутри
 * транзакции теста — она откатывается.
 */
@SpringBootTest
@Transactional
class CompanyProfileApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired CompanyProfileService service;
    @Autowired CompanyProfileRepository repository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    private String profileJson() throws Exception {
        return mvc.perform(get("/api/company-profile").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** Убрать картинку рынка через сервис — в транзакции теста, откатится. */
    private void removeImage(Market market, CompanyImageKind kind) {
        MarketContext.set(market);
        try {
            service.deleteImage(kind);
        } finally {
            MarketContext.clear();
        }
    }

    /**
     * Счётчик «исх. №» в таблице. Сперва сброс изменений JPA: сохранение сущности попадает в базу только при сбросе,
     * а транзакция теста не фиксируется — без него откат номера сущностью сюда просто не доехал бы.
     */
    private int storedCounter(Market market) {
        em.flush();
        return jdbc.queryForObject("SELECT next_number FROM company_profile WHERE market = ?", Integer.class,
                market.name());
    }

    /** Ставка для сравнения по значению: 5 (из jsonb) и 5.00 (из NUMERIC) — одна ставка; null — «Без НДС». */
    private static String rate(BigDecimal r) {
        return r == null ? "Без НДС" : r.stripTrailingZeros().toPlainString();
    }

    private static List<String> rates(List<BigDecimal> rates) {
        List<String> out = new ArrayList<>();
        for (BigDecimal r : rates) out.add(rate(r));
        return out;
    }

    private static List<String> rates(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(rate(n.isNull() ? null : n.decimalValue()));
        return out;
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsProfileOfHisMarketButCannotChangeIt() throws Exception {
        CompanyProfile kz = repository.findByMarket(Market.KZ).orElseThrow();
        CompanyProfile rf = repository.findByMarket(Market.RF).orElseThrow();
        String json = mvc.perform(get("/api/company-profile").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("KZ"))
                .andExpect(jsonPath("$.currency").value("KZT"))
                .andExpect(jsonPath("$.shortName").value(kz.getShortName()))
                .andExpect(jsonPath("$.vatRates.length()").value(kz.getVatRates().size()))
                .andExpect(jsonPath("$.hasStamp").value(kz.getStampPng() != null))
                .andExpect(jsonPath("$.stampPng").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        // каждая ставка — на своём месте и по значению, «Без НДС» (null) тоже
        assertThat(rates(om.readTree(json).get("vatRates"))).isEqualTo(rates(kz.getVatRates()));
        mvc.perform(get("/api/company-profile").header("X-Market", "RF"))
                .andExpect(jsonPath("$.market").value("RF"))
                .andExpect(jsonPath("$.shortName").value(rf.getShortName()));
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(profileJson()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminUpdatesFieldsAndRatesAreChecked() throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(profileJson());
        body.put("phone", "8 777 000 00 00");
        body.put("nextNumber", 444);
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("8 777 000 00 00"))
                .andExpect(jsonPath("$.nextNumber").value(444));
        assertThat(storedCounter(Market.KZ)).isEqualTo(444);   // явная правка номера — в таблице

        // Список ставок — явно, а не сохранённый в nirdb: «12% нет в списке» не должно зависеть от правок оператора.
        // Ставки РУ и «не подлежит» — из этого же списка, иначе отказ без «Наименования» ниже мог бы прийти из-за них.
        body.putArray("vatRates").add(5).add(16).addNull();
        body.put("vatRegistered", 5);
        body.put("vatNotRegistrable", 16);
        body.put("vatDefault", 12);   // 12% нет в списке [5, 16, без НДС]
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("12%")));

        body.put("vatDefault", 5);
        body.putArray("defaultColumns").addObject().put("key", "SUM");   // без «Наименования»
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Наименование")));
    }

    /**
     * Реквизиты открыты в одной вкладке, в другой выдаются номера КП, потом здесь правят телефон. Сохранение без поля
     * nextNumber счётчик не трогает: раньше пропущенное поле становилось 1, а профиль целиком возвращал старый номер.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void savingRequisitesDoesNotRollTheCounterBack() throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(profileJson());   // страница реквизитов загрузила профиль
        int original = storedCounter(Market.KZ);
        service.allocateNumber(Market.KZ);                            // два КП из другой вкладки
        service.allocateNumber(Market.KZ);
        em.clear();   // следующий запрос страницы читает профиль заново, как отдельный запрос в приложении
        body.remove("nextNumber");
        body.put("phone", "8 777 111 11 11");
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("8 777 111 11 11"))
                .andExpect(jsonPath("$.nextNumber").value(original + 2));
        assertThat(storedCounter(Market.KZ)).isEqualTo(original + 2);
    }

    /**
     * Профиль прочитан до выдачи номера (соседняя вкладка выдала номер между чтением и сохранением), потом загружена
     * печать. Сущность сохраняется целиком, но next_number через JPA не обновляется — номер не откатывается.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void imageUploadDoesNotRollTheCounterBack() throws Exception {
        int original = repository.findByMarket(Market.KZ).orElseThrow().getNextNumber();   // сущность — в сессии JPA
        service.allocateNumber(Market.KZ);                                                  // её номер устарел
        MockMultipartFile file = new MockMultipartFile("file", "stamp.jpg", "image/jpeg",
                KpTestSupport.circleOnWhiteJpeg(400));
        mvc.perform(multipart("/api/company-profile/images/stamp").file(file).header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasStamp").value(true));
        assertThat(storedCounter(Market.KZ)).isEqualTo(original + 1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminUploadsStampSeesItAndDeletes() throws Exception {
        // предусловия ниже («логотипа нет», «у Регион-Мед печати нет») — явно: живые проверки могли их оставить
        removeImage(Market.KZ, CompanyImageKind.LOGO);
        removeImage(Market.RF, CompanyImageKind.STAMP);

        MockMultipartFile file = new MockMultipartFile("file", "stamp.jpg", "image/jpeg",
                KpTestSupport.circleOnWhiteJpeg(400));
        mvc.perform(multipart("/api/company-profile/images/stamp").file(file).param("removeBackground", "true")
                        .header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasStamp").value(true))
                .andExpect(jsonPath("$.hasLogo").value(false));
        byte[] png = mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        // печать West-Med не видна из Регион-Мед
        mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "RF")).andExpect(status().isNotFound());

        mvc.perform(delete("/api/company-profile/images/stamp").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasStamp").value(false));
        mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "KZ")).andExpect(status().isNotFound());
        mvc.perform(get("/api/company-profile/images/nope").header("X-Market", "KZ")).andExpect(status().isNotFound());
    }

    @Test
    void allocateNumberIsSequentialPerMarket() {
        int a = service.allocateNumber(Market.KZ);
        int b = service.allocateNumber(Market.KZ);
        int rf = service.allocateNumber(Market.RF);
        assertThat(b).isEqualTo(a + 1);
        assertThat(rf).isPositive();
    }
}
