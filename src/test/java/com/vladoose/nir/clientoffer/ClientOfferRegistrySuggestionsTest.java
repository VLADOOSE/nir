package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Подсказки РУ для строк КП (спека 2026-10-13 §2.3, решение плана 6). */
@SpringBootTest
@Transactional
class ClientOfferRegistrySuggestionsTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired MedRegistryRepository registry;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        registry.save(MedRegistry.builder().regNumber("ZZ-KPS-1").name("Электрокардиограф ZZKPSUG R900, ZZKPSUG R700")
                .producer("ZZKPSUGVENDOR Ltd").country("КИТАЙ").unlimited(true).build());
        registry.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static String row(String key, String name, String model, String manufacturer) {
        return "{\"key\":\"" + key + "\",\"name\":" + (name == null ? "null" : "\"" + name + "\"")
                + ",\"model\":" + (model == null ? "null" : "\"" + model + "\"")
                + ",\"manufacturer\":" + (manufacturer == null ? "null" : "\"" + manufacturer + "\"") + "}";
    }

    private Map<String, JsonNode> suggest(String market, String... rows) throws Exception {
        String body = "{\"rows\":[" + String.join(",", rows) + "]}";
        JsonNode r = om.readTree(mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", market)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        Map<String, JsonNode> byKey = new HashMap<>();
        r.forEach(n -> byKey.put(n.get("key").asText(), n));
        return byKey;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void sameModelSuggested_blankNameSkipped() throws Exception {
        Map<String, JsonNode> r = suggest("KZ", row("a", "Электрокардиограф ZZKPSUG R900", null, "ZZKPSUGVENDOR"),
                row("b", "  ", null, "ZZKPSUGVENDOR"));

        assertThat(r.get("a").get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(r.get("a").get("regNumber").asText()).isEqualTo("ZZ-KPS-1");
        assertThat(r.get("a").get("score").decimalValue()).isBetween(new BigDecimal("0.75"), BigDecimal.ONE);
        assertThat(r.get("a").get("registry").get("name").asText()).contains("ZZKPSUG R900");
        assertThat(r).doesNotContainKey("b");
    }

    /** Review Focus 4: код модели только в поле «Модель» находится; соседняя модель того же бренда — «нет в реестре». */
    @Test
    @WithMockUser(roles = "ADMIN")
    void modelCodeFromModelField_otherModelNotFound() throws Exception {
        Map<String, JsonNode> r = suggest("KZ", row("m", "Электрокардиограф ZZKPSUG", "R900", "ZZKPSUGVENDOR"),
                row("x", "Электрокардиограф ZZKPSUG R12", null, "ZZKPSUGVENDOR"));

        assertThat(r.get("m").get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(r.get("x").get("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(r.get("x").get("regNumber").isNull()).isTrue();
    }

    /**
     * Ревью ветки (I2): наименование длиннее ~70 символов — поиск кандидатов режет текст до 80, и модель в конце из него
     * пропадала (0,733 < 0,75 → «не найдено» при верной записи первой). Модель — в начало текста подсказки.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void longNameWithModelField_modelKeptInSearch() throws Exception {
        Map<String, JsonNode> r = suggest("KZ", row("l",
                "Электрокардиограф ZZKPSUG с автоматической интерпретацией и встроенным термопринтером для кабинета",
                "R900", "ZZKPSUGVENDOR"));

        assertThat(r.get("l").get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(r.get("l").get("regNumber").asText()).isEqualTo("ZZ-KPS-1");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rfIs400() throws Exception {
        mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", "RF")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[" + row("a", "Монитор", null, null) + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void moreThan500RowsIs400() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 501; i++) rows.append(i == 0 ? "" : ",").append(row("k" + i, "Позиция " + i, null, null));
        mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[" + rows + "]}"))
                .andExpect(status().isBadRequest());
    }

    /** Review Focus 5 (часть): оператор подсказки не запрашивает — 403. */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotRequestSuggestions() throws Exception {
        mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isForbidden());
    }
}
