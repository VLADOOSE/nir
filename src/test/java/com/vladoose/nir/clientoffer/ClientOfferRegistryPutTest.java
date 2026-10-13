package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUT КП: регистрация строки через реестр (спека 2026-10-13 §4, решения плана 1, 2, 7). */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class ClientOfferRegistryPutTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientOfferService offers;
    @Autowired ClientOfferRepository offerRepo;
    @Autowired CompanyProfileRepository profiles;
    @Autowired MedRegistryRepository registry;
    @Autowired MedEquipmentRepository equipment;
    MockMvc mvc;
    ClientOffer kp;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        CompanyProfile p = profiles.findByMarket(Market.KZ).orElseThrow();   // реквизиты правит оператор — явно, с откатом
        p.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        p.setVatDefault(new BigDecimal("16"));
        p.setVatRegistered(new BigDecimal("5"));
        p.setVatNotRegistrable(new BigDecimal("16"));
        registry.save(MedRegistry.builder().regNumber("ZZ-KPR-1").name("Дефибриллятор ZZKPR ДКИ-Н-99").producer("ZZKPR Концерн")
                .country("РОССИЯ").regDate(LocalDate.of(2024, 1, 15)).unlimited(true).build());
        registry.save(MedRegistry.builder().regNumber("ZZ-KPR-2").name("Отсасыватель ZZKPR ОМХ-9").producer("ZZKPR Концерн")
                .country("РОССИЯ").regDate(LocalDate.of(2021, 6, 28)).expirationDate(LocalDate.of(2026, 6, 28)).unlimited(false).build());
        registry.flush();
        kp = offers.create("test");
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private JsonNode getOffer() throws Exception {
        return om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Тело PUT = ответ GET + одна новая позиция. */
    private ObjectNode bodyWithNewRow(ObjectNode row) throws Exception {
        ObjectNode body = getOffer().deepCopy();
        ((ArrayNode) body.get("items")).add(row);
        return body;
    }

    private ObjectNode newRow(String status, String regNumber) {
        ObjectNode r = om.createObjectNode();
        r.put("key", "n" + System.nanoTime());
        r.put("kind", "ITEM");
        r.put("name", "ZZ Позиция");
        r.put("quantity", 1);
        r.put("unit", "шт");
        r.put("vatRate", 5);
        r.put("registrationStatus", status);
        if (regNumber != null) r.put("regNumber", regNumber);
        return r;
    }

    private ResultActions putOffer(ObjectNode body) throws Exception {
        return mvc.perform(put("/api/client-offers/" + kp.getId()).header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private ClientOfferItem storedLast() {
        em.flush();
        em.clear();
        List<ClientOfferItem> items = offerRepo.findById(kp.getId()).orElseThrow().getItems();
        return items.get(items.size() - 1);
    }

    @Test
    void confirmFromRegistry_textBuiltByServer_clientTextIgnored() throws Exception {
        ObjectNode row = newRow("CONFIRMED", "ZZ-KPR-1");
        row.put("registrationText", "подделка");

        putOffer(bodyWithNewRow(row)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[-1:].registry.regNumber").value(org.hamcrest.Matchers.hasItem("ZZ-KPR-1")));

        ClientOfferItem it = storedLast();
        assertThat(it.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.CONFIRMED);
        assertThat(it.getRegNumber()).isEqualTo("ZZ-KPR-1");
        assertThat(it.getRegistrationText()).isEqualTo("№ ZZ-KPR-1 от 15.01.2024 г., бессрочно");
    }

    @Test
    void unknownNumberIs400() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-НЕТ"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("РУ не найдено в реестре: ZZ-KPR-НЕТ")));
    }

    @Test
    void newConfirmedWithoutNumberIs400() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Не указан № РУ")));
    }

    @Test
    void rfOfferIs400() throws Exception {
        MarketContext.set(Market.RF);
        CompanyProfile rfProfile = profiles.findByMarket(Market.RF).orElseThrow();   // ставки РФ — явно, с откатом
        rfProfile.setVatRates(new ArrayList<>(Arrays.asList(null, new BigDecimal("10"), new BigDecimal("22"))));
        ClientOffer rf = offers.create("test");
        em.flush();
        ObjectNode body = om.readTree(mvc.perform(get("/api/client-offers/" + rf.getId()).header("X-Market", "RF"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).deepCopy();
        ObjectNode row = newRow("NOT_FOUND", null);
        row.putNull("vatRate");
        ((ArrayNode) body.get("items")).add(row);

        mvc.perform(put("/api/client-offers/" + rf.getId()).header("X-Market", "RF")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("только для Казахстана")));
    }

    @Test
    void suggestedAndNotFoundAreSaved() throws Exception {
        ObjectNode sug = newRow("SUGGESTED", "ZZ-KPR-1");
        sug.put("suggestionScore", 0.8125);
        putOffer(bodyWithNewRow(sug)).andExpect(status().isOk());
        ClientOfferItem s = storedLast();
        assertThat(s.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.SUGGESTED);
        assertThat(s.getRegNumber()).isEqualTo("ZZ-KPR-1");
        assertThat(s.getSuggestionScore()).isEqualByComparingTo("0.813");
        assertThat(s.getRegistrationText()).isNull();

        putOffer(bodyWithNewRow(newRow("NOT_FOUND", "ZZ-KPR-1"))).andExpect(status().isOk());
        ClientOfferItem nf = storedLast();
        assertThat(nf.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.NOT_FOUND);
        assertThat(nf.getRegNumber()).isNull();
        assertThat(nf.getRegistrationText()).isNull();
    }

    @Test
    void scoreOutOfRangeIs400() throws Exception {
        ObjectNode sug = newRow("SUGGESTED", "ZZ-KPR-1");
        sug.put("suggestionScore", 1.5);
        putOffer(bodyWithNewRow(sug)).andExpect(status().isBadRequest());
    }

    /** Review Focus 1: тот же № у подтверждённой строки не перечитывается — даже если записи в реестре больше нет. */
    @Test
    void sameNumberKeepsSnapshotEvenIfRecordGone() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-1"))).andExpect(status().isOk());
        Long id = storedLast().getId();
        jdbc.update("update client_offer_item set registration_text = 'старый текст' where id = ?", id);
        jdbc.update("delete from med_registry where reg_number = 'ZZ-KPR-1'");

        putOffer(getOffer().deepCopy()).andExpect(status().isOk());

        assertThat(storedLast().getRegistrationText()).isEqualTo("старый текст");
    }

    /** Review Focus 5: старая вкладка не шлёт regNumber — тот же статус без № значит «не менялось». */
    @Test
    void missingNumberWithSameStatusKeeps() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-1"))).andExpect(status().isOk());
        ObjectNode body = getOffer().deepCopy();
        ObjectNode last = (ObjectNode) body.get("items").get(body.get("items").size() - 1);
        last.remove("regNumber");

        putOffer(body).andExpect(status().isOk());

        ClientOfferItem it = storedLast();
        assertThat(it.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.CONFIRMED);
        assertThat(it.getRegNumber()).isEqualTo("ZZ-KPR-1");
    }

    @Test
    void responseCarriesRecordWithExpiry() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-2"))).andExpect(status().isOk());
        JsonNode items = getOffer().get("items");
        JsonNode last = items.get(items.size() - 1);
        assertThat(last.get("registry").get("expirationDate").asText()).isEqualTo("2026-06-28");
        assertThat(last.get("registry").get("unlimited").asBoolean()).isFalse();
        assertThat(last.get("registrationText").asText()).isEqualTo("№ ZZ-KPR-2 от 28.06.2021 г., до 28.06.2026 г.");
    }

    @Test
    void catalogLinkOnNewRow_ownMarket_keptAfterwards() throws Exception {
        MedEquipment kzItem = equipment.save(MedEquipment.builder().name("ZZ Каталог KZ").manufact("ZZ").market(Market.KZ).build());
        ObjectNode row = newRow("UNCHECKED", null);
        row.put("medEquipmentId", kzItem.getId());
        putOffer(bodyWithNewRow(row)).andExpect(status().isOk());
        assertThat(storedLast().getMedEquipmentId()).isEqualTo(kzItem.getId());

        MedEquipment other = equipment.save(MedEquipment.builder().name("ZZ Каталог KZ 2").manufact("ZZ").market(Market.KZ).build());
        ObjectNode body = getOffer().deepCopy();
        ((ObjectNode) body.get("items").get(body.get("items").size() - 1)).put("medEquipmentId", other.getId());
        putOffer(body).andExpect(status().isOk());
        assertThat(storedLast().getMedEquipmentId()).isEqualTo(kzItem.getId());   // у сохранённой строки с клиента не меняется
    }

    /** Review Focus 3: позиция каталога другого рынка — 400. */
    @Test
    void catalogLinkOtherMarketIs400() throws Exception {
        MedEquipment rfItem = equipment.save(MedEquipment.builder().name("ZZ Каталог RF").manufact("ZZ").market(Market.RF).build());
        ObjectNode row = newRow("UNCHECKED", null);
        row.put("medEquipmentId", rfItem.getId());

        putOffer(bodyWithNewRow(row)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Позиция каталога не найдена")));
    }

    @Test
    void notFoundCountsAsUnconfirmed() throws Exception {
        putOffer(bodyWithNewRow(newRow("NOT_FOUND", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.unconfirmedRegistrationCount").value(1));
    }
}
