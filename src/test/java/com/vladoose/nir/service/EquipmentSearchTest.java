package com.vladoose.nir.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Поиск по каталогу для «Из реестра и каталога» (спека 2026-10-13 §2.4). */
@SpringBootTest
@Transactional
@WithMockUser(roles = "OPERATOR")
class EquipmentSearchTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired MedEquipmentRepository equipment;
    @Autowired MedRegistryRepository registry;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        MedRegistry rec = registry.save(MedRegistry.builder().regNumber("ZZ-EQS-1").name("Аппарат ZZEQS").producer("ZZEQS")
                .country("РОССИЯ").unlimited(true).build());
        MarketContext.set(Market.KZ);
        MedEquipment kz = equipment.save(MedEquipment.builder().name("Аппарат ZZEQS KZ").manufact("ZZEQSVENDOR").build());
        kz.setRegistration(rec);
        equipment.save(MedEquipment.builder().name("Прибор 100% ZZEQS_X").manufact("ZZEQSVENDOR").build());
        // «_» и «%» без экранирования — любой символ / любая строка: эти две нашлись бы по «S_X» и «100%»
        equipment.save(MedEquipment.builder().name("ZZEQSAX").manufact("ZZEQSVENDOR").build());
        equipment.save(MedEquipment.builder().name("Прибор 1009 ZZEQS").manufact("ZZEQSVENDOR").build());
        MarketContext.set(Market.RF);
        equipment.save(MedEquipment.builder().name("Аппарат ZZEQS RF").manufact("ZZEQSVENDOR").build());
        equipment.flush();
        MarketContext.set(Market.KZ);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private List<String> names(String market, String q) throws Exception {
        JsonNode r = om.readTree(mvc.perform(get("/api/equipment/search").param("q", q).header("X-Market", market))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> out = new ArrayList<>();
        r.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    @Test
    void ownMarketOnly_byNameAndManufacturer() throws Exception {
        assertThat(names("KZ", "zzeqs")).contains("Аппарат ZZEQS KZ").doesNotContain("Аппарат ZZEQS RF");
        assertThat(names("KZ", "zzeqsvendor")).contains("Аппарат ZZEQS KZ");
        assertThat(names("RF", "zzeqs")).containsExactly("Аппарат ZZEQS RF");
    }

    @Test
    void registryIncludedWhenLinked() throws Exception {
        JsonNode r = om.readTree(mvc.perform(get("/api/equipment/search").param("q", "Аппарат ZZEQS KZ").header("X-Market", "KZ"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.get(0).get("registry").get("regNumber").asText()).isEqualTo("ZZ-EQS-1");
    }

    @Test
    void shortQueryEmpty_likeWildcardsEscaped() throws Exception {
        assertThat(names("KZ", "z")).isEmpty();
        assertThat(names("KZ", "100%")).containsExactly("Прибор 100% ZZEQS_X");
        assertThat(names("KZ", "S_X")).containsExactly("Прибор 100% ZZEQS_X");
    }
}
