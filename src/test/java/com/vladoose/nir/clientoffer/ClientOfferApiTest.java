package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.repository.FacilityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API «КП клиентам» (спека §10–§12): права, рынок, автосохранение с версией, строки, проверка ввода, файлы.
 * Реквизиты в nirdb — данные оператора (их правят живые проверки и сам оператор): ставки НДС и наценку по умолчанию, на
 * которых стоят суммы тестов, тест ставит сам — в своей транзакции, она откатывается; остальное ожидаемое берётся из
 * строки рынка.
 */
@SpringBootTest
@Transactional
class ClientOfferApiTest {

    private static final String[] ALL_COLUMNS = {"NUM", "NAME", "MODEL", "MANUFACTURER", "COUNTRY", "UNIT", "QTY",
            "PRICE", "PRICE_NET", "VAT_RATE", "VAT_SUM", "SUM_NET", "SUM", "REGISTRATION", "NOTE"};

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired FacilityRepository facilities;
    @Autowired CompanyProfileRepository profiles;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        // ставки West-Med [5, 16, без НДС] и наценка 20 % — явно: «12% нет в списке» и «126 000,00» не зависят от правок
        // реквизитов; сущность в сессии теста — сервисы читают её же
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setDefaultMarkupPct(new BigDecimal("20.00"));
    }

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    private static MockHttpServletRequestBuilder kz(MockHttpServletRequestBuilder b) {
        return b.header("X-Market", "KZ");
    }

    /** Ответ 200 как JSON; тело — UTF-8 явно (кириллица в названиях). */
    private JsonNode json(MockHttpServletRequestBuilder b) throws Exception {
        return om.readTree(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode create() throws Exception {
        return json(kz(post("/api/client-offers")));
    }

    /** Тело PUT = ответ GET с правками: лишние поля (id, totals, calc…) сервер игнорирует. */
    private ObjectNode bodyOf(JsonNode offer) {
        return offer.deepCopy();
    }

    private ObjectNode item(String key, String name, int qty, Integer purchase, Integer vat) {
        ObjectNode it = om.createObjectNode();
        it.put("key", key);
        it.put("kind", "ITEM");
        it.put("name", name);
        it.put("unit", "шт");
        it.put("quantity", qty);
        if (purchase != null) it.put("purchasePrice", purchase); else it.putNull("purchasePrice");
        it.put("purchaseVatSame", true);
        if (vat != null) it.put("vatRate", vat); else it.putNull("vatRate");
        it.put("registrationStatus", "UNCHECKED");
        return it;
    }

    private MockHttpServletRequestBuilder putOffer(long id, ObjectNode body) {
        return kz(put("/api/client-offers/" + id)).contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }

    private JsonNode save(long id, ObjectNode body) throws Exception {
        return json(putOffer(id, body));
    }

    /** PUT отклонён с 400, и причина — в тексте ошибки (не «Внутренняя ошибка сервера» и не общий текст про базу). */
    private void rejected(long id, ObjectNode body, String reason) throws Exception {
        mvc.perform(putOffer(id, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(reason)));
    }

    private static ArrayNode columns(ObjectNode body, String... keys) {
        ArrayNode columns = body.putArray("columns");
        for (String key : keys) columns.addObject().put("key", key);
        return columns;
    }

    /** Скачивание, а не показ; русское имя файла — RFC 5987 (filename*=UTF-8''…) и читается обратно без потерь. */
    private static void assertDownload(MockHttpServletResponse response, String fileName) {
        String header = response.getHeader("Content-Disposition");
        assertThat(header).startsWith("attachment;").contains("filename*=UTF-8''%D0%9A%D0%9F");   // «КП»
        assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo(fileName);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorSeesJournalButCannotWrite() throws Exception {
        mvc.perform(kz(get("/api/client-offers"))).andExpect(status().isOk());
        mvc.perform(kz(post("/api/client-offers"))).andExpect(status().isForbidden());
        mvc.perform(kz(get("/api/company-profile/sample-preview"))).andExpect(status().isForbidden());
        // запись по id — тоже только администратору: права проверяются раньше, чем ищется КП
        mvc.perform(kz(delete("/api/client-offers/1"))).andExpect(status().isForbidden());
        mvc.perform(kz(post("/api/client-offers/1/duplicate"))).andExpect(status().isForbidden());
        mvc.perform(kz(post("/api/client-offers/1/status")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SENT\"}")).andExpect(status().isForbidden());
    }

    /**
     * Автосохранение — только администратору: оператор с правильным телом получает 403, и КП не меняется. Тело правильное
     * нарочно: неправильное отбила бы проверка тела (400) раньше прав.
     */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotSaveAnOffer() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")).with(user("admin").roles("ADMIN")));
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.put("subject", "Правка оператора");
        mvc.perform(putOffer(id, body)).andExpect(status().isForbidden());
        JsonNode stored = json(kz(get("/api/client-offers/" + id)));
        assertThat(stored.get("version").asInt()).isZero();
        assertThat(textOrNull(stored.get("subject"))).isNull();
    }

    /** КП другого рынка — 404 и на автосохранение, и на смену статуса; само КП не меняется. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void saveAndStatusOfAnotherMarketAreNotFound() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.put("subject", "Правка из другого рынка");
        mvc.perform(put("/api/client-offers/" + id).header("X-Market", "RF")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isNotFound());
        mvc.perform(post("/api/client-offers/" + id + "/status").header("X-Market", "RF")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SENT\"}")).andExpect(status().isNotFound());
        JsonNode stored = json(kz(get("/api/client-offers/" + id)));
        assertThat(stored.get("status").asText()).isEqualTo("DRAFT");
        assertThat(stored.get("version").asInt()).isZero();
        assertThat(textOrNull(stored.get("subject"))).isNull();
    }

    /** Клиент своего рынка сохраняется: в ответе его название, и имя файла КП — по нему. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void clientOfTheSameMarketIsSaved() throws Exception {
        MarketContext.set(Market.KZ);
        Facility kz = facilities.save(Facility.builder().name("Клиника KZ " + UUID.randomUUID()).build());
        MarketContext.clear();
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        body.put("facilityId", kz.getId());
        JsonNode r = save(o.get("id").asLong(), body);
        assertThat(r.get("facilityId").asLong()).isEqualTo(kz.getId());
        assertThat(r.get("facilityName").asText()).isEqualTo(kz.getName());
        assertThat(r.get("fileBaseName").asText()).endsWith(" — " + kz.getName());
    }

    /**
     * Ставку убрали из настроек рынка — сохранённая с ней строка остаётся, и КП сохраняется дальше; новой строке эту
     * ставку уже не выбрать (400). 16 % убираются в транзакции теста — она откатывается.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void savedLineKeepsARateRemovedFromTheMarketButANewLineCannotTakeIt() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("k1", "Гигрометр", 1, 100, 16));
        JsonNode saved = save(id, body);
        profiles.findByMarket(Market.KZ).orElseThrow()
                .setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), null)));   // 16 % в списке больше нет

        ObjectNode next = bodyOf(saved);
        ((ObjectNode) next.get("items").get(0)).put("quantity", 2);
        JsonNode r = save(id, next);
        assertThat(r.get("items").get(0).get("vatRate").decimalValue()).isEqualByComparingTo("16");
        assertThat(r.get("items").get(0).get("quantity").decimalValue()).isEqualByComparingTo("2");

        ObjectNode added = bodyOf(r);
        ((ArrayNode) added.get("items")).add(item("k2", "Термоконтейнер", 1, 100, 16));
        rejected(id, added, "Ставки НДС «16%» нет в настройках рынка");

        ObjectNode switched = bodyOf(r);   // сохранённая строка держит только свою ставку — на другую вне списка не сменить
        ((ObjectNode) switched.get("items").get(0)).put("vatRate", 12);
        rejected(id, switched, "Ставки НДС «12%» нет в настройках рынка");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createTakesMarketDefaultsAndNextNumber() throws Exception {
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        int next = jdbc.queryForObject("select next_number from company_profile where market = 'KZ'", Integer.class);
        JsonNode o = create();
        assertThat(o.get("number").asInt()).isEqualTo(next);
        assertThat(o.get("status").asText()).isEqualTo("DRAFT");
        assertThat(o.get("version").asInt()).isZero();
        assertThat(o.get("currency").asText()).isEqualTo("KZT");
        assertThat(o.get("columns")).hasSize(kz.getDefaultColumns().size());
        assertThat(o.get("columns").get(0).get("key").asText()).isEqualTo(kz.getDefaultColumns().get(0).getKey());
        assertThat(o.get("terms")).hasSize(kz.getDefaultTerms().size());
        assertThat(o.get("termsStyle").asText()).isEqualTo(kz.getDefaultTermsStyle().name());
        assertThat(textOrNull(o.get("intro"))).isEqualTo(kz.getDefaultIntro());
        assertThat(o.get("defaultMarkupPct").decimalValue()).isEqualByComparingTo("20");
        assertThat(o.get("items")).isEmpty();
        assertThat(o.get("fileBaseName").asText()).startsWith("КП № " + next + " от ");
        assertThat(create().get("number").asInt()).isEqualTo(next + 1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void putCalculatesItemsEchoesKeysAndBumpsVersion() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ObjectNode line = item("k1", "Пульсоксиметр", 3, 105000, 5);
        // связи волны 2 PUT не принимает: будь они взяты, несуществующие id упали бы на внешних ключах
        line.put("distributorId", 999_999_999L);
        line.put("tenderLotId", 999_999_999L);
        line.put("priceRequestItemId", 999_999_999L);
        line.put("medEquipmentId", 999_999_999L);
        body.putArray("items").add(line);
        JsonNode r = save(id, body);
        JsonNode first = r.get("items").get(0);
        assertThat(first.get("id").isNumber()).isTrue();
        assertThat(first.get("key").asText()).isEqualTo("k1");
        assertThat(first.get("lineNo").asInt()).isEqualTo(1);
        assertThat(first.get("calc").get("price").decimalValue()).isEqualByComparingTo("126000.00");
        assertThat(r.get("totals").get("sum").decimalValue()).isEqualByComparingTo("378000.00");
        assertThat(r.get("totals").get("profit").decimalValue()).isEqualByComparingTo("60000.00");
        assertThat(r.get("version").asInt()).isEqualTo(1);
        Map<String, Object> links = jdbc.queryForMap("select distributor_id, tender_lot_id, price_request_item_id, "
                + "med_equipment_id from client_offer_item where id = ?", first.get("id").asLong());
        assertThat(links.values()).containsOnlyNulls();
        // журнал видит итог без загрузки строк
        mvc.perform(kz(get("/api/client-offers")).param("q", "пульсоксиметр"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].totalAmount").value(378000.0))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].itemCount").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staleVersionIsConflict() throws Exception {
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        save(o.get("id").asLong(), body);                          // версия 0 → 1
        mvc.perform(putOffer(o.get("id").asLong(), body))           // снова с версией 0
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("другой вкладке")));
    }

    /**
     * Две вкладки сохранили одновременно: проверку версии из тела эта запись прошла, но строку в базе уже изменила другая —
     * @Version отбивает запись. Это 409 «обновите страницу», а не 500.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void concurrentSaveIsConflictNotServerError() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        jdbc.update("update client_offer set version = version + 1 where id = ?", id);   // другая вкладка записала
        mvc.perform(putOffer(id, bodyOf(o)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Данные изменились в другой вкладке")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validationOfRatesQuantityAndRegistration() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("k1", "А", 1, 100, 12));
        rejected(id, body, "12%");

        body.putArray("items").add(item("k1", "А", 0, 100, 5));
        rejected(id, body, "Количество");

        ObjectNode noQuantity = item("k1", "А", 1, 100, 5);   // у позиции количество обязательно (спека §12: больше нуля)
        noQuantity.putNull("quantity");
        body.putArray("items").add(noQuantity);
        rejected(id, body, "Количество должно быть больше нуля — позиция № 1 «А»");

        ObjectNode confirmed = item("k1", "А", 1, 100, 5);
        confirmed.put("registrationStatus", "CONFIRMED");
        body.putArray("items").add(confirmed);
        rejected(id, body, "реестр");

        ObjectNode notRequired = item("k1", "Гигрометр", 1, 100, 16);
        notRequired.put("registrationStatus", "NOT_REQUIRED");
        ObjectNode manual = item("k2", "Пульсоксиметр", 1, 100, 5);
        manual.put("registrationText", "  № РК-МИ (МТ)-0№023037  ");
        body.putArray("items").add(notRequired).add(manual);
        JsonNode r = save(id, body);
        assertThat(r.get("items").get(0).get("registrationText").asText()).isEqualTo("Не подлежит регистрации");
        assertThat(r.get("items").get(1).get("registrationStatus").asText()).isEqualTo("MANUAL");
        assertThat(r.get("items").get(1).get("registrationText").asText()).isEqualTo("№ РК-МИ (МТ)-0№023037");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void removesMissingRowsAndKeepsNewOrder() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("a", "Первая", 1, 100, 5)).add(item("b", "Вторая", 1, 100, 5)).add(item("c", "Третья", 1, 100, 5));
        JsonNode r = save(id, body);
        ObjectNode third = (ObjectNode) r.get("items").get(2).deepCopy();
        ObjectNode firstRow = (ObjectNode) r.get("items").get(0).deepCopy();
        ObjectNode next = bodyOf(r);
        next.putArray("items").add(third).add(firstRow);
        JsonNode r2 = save(id, next);
        assertThat(r2.get("items")).hasSize(2);
        assertThat(r2.get("items").get(0).get("name").asText()).isEqualTo("Третья");
        assertThat(r2.get("items").get(0).get("lineNo").asInt()).isEqualTo(1);
        assertThat(r2.get("items").get(1).get("name").asText()).isEqualTo("Первая");
        assertThat(r2.get("items").get(1).get("id").asLong()).isEqualTo(firstRow.get("id").asLong());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void clientOfAnotherMarketIsRejected() throws Exception {
        MarketContext.set(Market.RF);
        Facility rf = facilities.save(Facility.builder().name("Клиника РФ " + UUID.randomUUID()).build());
        MarketContext.clear();
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        body.put("facilityId", rf.getId());
        rejected(o.get("id").asLong(), body, "Клиент не найден");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void offerOfAnotherMarketIsNotFound() throws Exception {
        long id = create().get("id").asLong();
        mvc.perform(get("/api/client-offers/" + id).header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/client-offers/" + id + "/pdf").header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/client-offers/" + id + "/docx").header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/client-offers/" + id + "/preview").header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/client-offers").header("X-Market", "RF"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(delete("/api/client-offers/" + id).header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(post("/api/client-offers/" + id + "/duplicate").header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(kz(get("/api/client-offers/" + id))).andExpect(status().isOk());   // чужой рынок не удалил
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void onlyDraftCanBeDeletedAndSentSetsSentAt() throws Exception {
        long id = create().get("id").asLong();
        JsonNode sent = json(kz(post("/api/client-offers/" + id + "/status")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SENT\"}"));
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("sentAt").isNull()).isFalse();
        assertThat(sent.get("version").asInt()).isEqualTo(1);
        mvc.perform(kz(delete("/api/client-offers/" + id))).andExpect(status().isBadRequest());

        long draft = create().get("id").asLong();
        mvc.perform(kz(delete("/api/client-offers/" + draft))).andExpect(status().isNoContent());
        mvc.perform(kz(get("/api/client-offers/" + draft))).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateCopiesRowsWithNewNumber() throws Exception {
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        body.put("recipient", "ГКП «Областная больница»");
        body.putArray("items").add(item("a", "Первая", 2, 100, 5)).add(item("b", "Вторая", 1, 200, 16));
        save(o.get("id").asLong(), body);
        JsonNode copy = json(kz(post("/api/client-offers/" + o.get("id").asLong() + "/duplicate")));
        assertThat(copy.get("id").asLong()).isNotEqualTo(o.get("id").asLong());
        assertThat(copy.get("number").asInt()).isEqualTo(o.get("number").asInt() + 1);
        assertThat(copy.get("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.get("recipient").asText()).isEqualTo("ГКП «Областная больница»");
        assertThat(copy.get("items")).hasSize(2);
        assertThat(copy.get("items").get(1).get("name").asText()).isEqualTo("Вторая");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void previewPdfAndDocx() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("a", "Пульсоксиметр", 1, 105000, 5));
        String fileBaseName = save(id, body).get("fileBaseName").asText();

        JsonNode preview = json(kz(get("/api/client-offers/" + id + "/preview")));
        byte[] png = Base64.getDecoder().decode(preview.get("pages").get(0).asText());
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isBetween(890, 930);

        MockHttpServletResponse pdf = mvc.perform(kz(get("/api/client-offers/" + id + "/pdf")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))   // свой, не умолчание Spring Security
                .andReturn().getResponse();
        assertDownload(pdf, fileBaseName + ".pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(KpTestSupport.text(pdf.getContentAsByteArray())).contains("Пульсоксиметр", "Итого: 126 000,00 тг");

        MockHttpServletResponse docx = mvc.perform(kz(get("/api/client-offers/" + id + "/docx")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))   // свой, не умолчание Spring Security
                .andReturn().getResponse();
        assertDownload(docx, fileBaseName + ".docx");
        assertThat(new String(docx.getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    /**
     * Предпросмотр говорит редактору, что таблица тесная (кегль ниже обычного): редактор подскажет «Альбомная».
     * Колонки — явно: умолчания реквизитов правит оператор.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void previewTellsWhenTheTableIsCrowded() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.put("landscape", false);
        body.putArray("items").add(item("a", "Пульсоксиметр", 1, 105000, 5));
        columns(body, "NUM", "NAME", "QTY", "PRICE", "SUM");
        JsonNode saved = save(id, body);
        assertThat(json(kz(get("/api/client-offers/" + id + "/preview"))).get("crowded").asBoolean()).isFalse();

        ObjectNode crowded = bodyOf(saved);
        columns(crowded, ALL_COLUMNS);   // все 15 колонок на книжном листе — кегль уменьшен
        save(id, crowded);
        JsonNode preview = json(kz(get("/api/client-offers/" + id + "/preview")));
        assertThat(preview.get("crowded").asBoolean()).isTrue();
        assertThat(preview.get("pages")).isNotEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void journalSearchByNumberAndStatusFilter() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        int number = o.get("number").asInt();
        mvc.perform(kz(get("/api/client-offers")).param("q", String.valueOf(number)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isNotEmpty());
        mvc.perform(kz(get("/api/client-offers")).param("status", "SENT"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(kz(get("/api/client-offers")).param("status", "DRAFT,SENT"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isNotEmpty());
        mvc.perform(kz(get("/api/client-offers")).param("status", "НЕТ"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void samplePreviewForAdmin() throws Exception {
        JsonNode r = json(kz(get("/api/company-profile/sample-preview")));
        assertThat(r.get("pages")).hasSize(1);
        assertThat(r.get("crowded").asBoolean()).isFalse();
        byte[] png = Base64.getDecoder().decode(r.get("pages").get(0).asText());
        assertThat(ImageIO.read(new ByteArrayInputStream(png))).isNotNull();
    }

    /**
     * Образец показывает каждую загруженную картинку — подпись и без печати; строка «не подлежит регистрации» — со ставкой
     * рынка для таких строк. Страницы сравниваются целиком: одинаковые данные дают те же байты PNG.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void samplePreviewFollowsSignatureAndNonRegistrableRate() throws Exception {
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setStampPng(null);
        kz.setSignaturePng(null);
        kz.setVatRegistered(new BigDecimal("5"));
        kz.setVatNotRegistrable(new BigDecimal("16"));
        String plain = json(kz(get("/api/company-profile/sample-preview"))).get("pages").get(0).asText();
        // без этого «страницы разные» ниже ничего бы не значило
        assertThat(json(kz(get("/api/company-profile/sample-preview"))).get("pages").get(0).asText()).isEqualTo(plain);

        kz.setSignaturePng(KpTestSupport.signaturePng());   // подпись есть, печати нет
        String signed = json(kz(get("/api/company-profile/sample-preview"))).get("pages").get(0).asText();
        assertThat(signed).isNotEqualTo(plain);

        kz.setVatNotRegistrable(new BigDecimal("5"));       // ставка «не подлежит» — та же, что у РУ: одна строка НДС
        String oneRate = json(kz(get("/api/company-profile/sample-preview"))).get("pages").get(0).asText();
        assertThat(oneRate).isNotEqualTo(signed);
    }

    /** Пустой элемент в колонках или условиях — 400 с причиной, а не 500 из сборщика документа. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void emptyColumnOrTermIsRejected() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        columns(body, "NAME").addNull();
        rejected(id, body, "Пустая колонка");

        body = bodyOf(o);
        body.putArray("terms").addNull();
        rejected(id, body, "Пустое условие");
    }

    /** Строка без вида и пустой элемент списка строк — 400 с причиной, а не 500. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void lineWithoutKindIsRejected() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ObjectNode noKind = item("k1", "Пульсоксиметр", 1, 100, 5);
        noKind.putNull("kind");
        body.putArray("items").add(noKind);
        rejected(id, body, "вид строки");

        body.putArray("items").addNull();
        rejected(id, body, "Пустая строка");
    }

    /** Ставка НДС строки и НДС закупки — только от 0 и меньше 100: на −100 расчёт делил бы на ноль (500). */
    @Test
    @WithMockUser(roles = "ADMIN")
    void vatRatesOutsideZeroToHundredAreRejected() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("k1", "Пульсоксиметр", 1, 100, -100));
        rejected(id, body, "меньше 100%");

        ObjectNode purchaseVat = item("k1", "Пульсоксиметр", 1, 100, 5);
        purchaseVat.put("purchaseVatSame", false);
        purchaseVat.put("purchaseVatRate", -100);
        body.putArray("items").add(purchaseVat);
        rejected(id, body, "НДС закупки");
    }

    /** Наценка строки — от −100 до 1000 %, цены — не отрицательные (спека §12); наценка КП — так же (проверка тела). */
    @Test
    @WithMockUser(roles = "ADMIN")
    void markupOutsideRangeAndNegativePricesAreRejected() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ObjectNode markup = item("k1", "Пульсоксиметр", 1, 100, 5);
        markup.put("markupPct", 1001);
        body.putArray("items").add(markup);
        rejected(id, body, "Наценка");

        body.putArray("items").add(item("k1", "Пульсоксиметр", 1, -1, 5));
        rejected(id, body, "Цена закупки не может быть отрицательной");

        ObjectNode price = item("k1", "Пульсоксиметр", 1, 100, 5);
        price.put("priceOverride", -1);
        body.putArray("items").add(price);
        rejected(id, body, "Цена клиенту не может быть отрицательной");

        ObjectNode offerMarkup = bodyOf(o);
        offerMarkup.put("defaultMarkupPct", -101);
        mvc.perform(putOffer(id, offerMarkup))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultMarkupPct").value(containsString("−100")));
    }

    /** Сумма строки больше NUMERIC(15,2) и прописи — 400 до сохранения, с номером позиции. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void lineSumThatDoesNotFitIsRejected() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ObjectNode line = item("k1", "Томограф", 3, null, 5);
        line.put("priceOverride", new BigDecimal("5000000000000"));   // 3 × 5 трлн = 15 трлн
        body.putArray("items").add(line);
        rejected(id, body, "Сумма позиции");
    }

    /** Каждая строка влезает, итог — нет: тоже 400 до сохранения (а не ошибка базы на total_amount). */
    @Test
    @WithMockUser(roles = "ADMIN")
    void offerTotalThatDoesNotFitIsRejected() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ArrayNode items = body.putArray("items");
        for (String key : new String[] {"a", "b"}) {
            ObjectNode line = item(key, "Томограф", 1, null, 5);
            line.put("priceOverride", new BigDecimal("6000000000000"));   // строка — 6 трлн, итог — 12 трлн
            items.add(line);
        }
        rejected(id, body, "Итог КП");
    }

    /** Цена закупки и количество, которые не помещаются в свои колонки, — 400 с причиной, даже когда итог нулевой. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void pricesAndQuantitiesThatDoNotFitTheirColumnsAreRejected() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ObjectNode price = item("k1", "Томограф", 1, null, 5);
        price.put("purchasePrice", new BigDecimal("10000000000000"));   // на копейку больше NUMERIC(15,2)
        price.put("markupPct", -100);                                    // цена клиенту 0 — итог влезает
        body.putArray("items").add(price);
        rejected(id, body, "Цена закупки");

        ObjectNode qty = item("k1", "Томограф", 1, null, 5);
        qty.put("quantity", new BigDecimal("1000000000"));   // миллиард — больше NUMERIC(12,3)
        qty.put("priceOverride", 0);
        body.putArray("items").add(qty);
        rejected(id, body, "Количество больше");
    }

    /**
     * Числа длиннее 15 цифр до запятой или 10 после — 400 проверкой тела, до округления: округление (setScale) числа вроде
     * 1e200000000 заняло бы процессор на минуты, 1e-20 молча стало бы нулём. Причина — в ошибке поля.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void numbersWithTooManyDigitsAreRejectedBeforeRounding() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ObjectNode markup = item("k1", "Пульсоксиметр", 1, 100, 5);
        markup.put("markupPct", new BigDecimal("1E-20"));
        body.putArray("items").add(markup);
        tooManyDigits(id, body, "items[0].markupPct");

        ObjectNode price = item("k1", "Пульсоксиметр", 1, null, 5);
        price.put("priceOverride", new BigDecimal("1E+20"));
        body.putArray("items").add(price);
        tooManyDigits(id, body, "items[0].priceOverride");

        ObjectNode huge = item("k1", "Пульсоксиметр", 1, 100, 5);
        huge.put("quantity", new BigDecimal("1E+200000000"));
        body.putArray("items").add(huge);
        assertTimeout(Duration.ofSeconds(5), () -> tooManyDigits(id, body, "items[0].quantity"));

        ObjectNode offerMarkup = bodyOf(o);
        offerMarkup.put("defaultMarkupPct", new BigDecimal("1E-200000000"));
        assertTimeout(Duration.ofSeconds(5), () -> tooManyDigits(id, offerMarkup, "defaultMarkupPct"));
    }

    private void tooManyDigits(long id, ObjectNode body, String field) throws Exception {
        mvc.perform(putOffer(id, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['" + field + "']").value(containsString("Слишком длинное число")));
    }

    /**
     * Числа — с точностью своих колонок (так их округлила бы база): ответ автосохранения посчитан от того же, что лежит в
     * базе, а количество 0,0004 не проходит «больше нуля», чтобы в базе стать нулём.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void numbersAreRoundedToTheirColumnsBeforeChecks() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        ObjectNode tiny = item("k1", "Пульсоксиметр", 1, 100, 5);
        tiny.put("quantity", new BigDecimal("0.0004"));
        body.putArray("items").add(tiny);
        rejected(id, body, "больше нуля");

        ObjectNode line = item("k1", "Пульсоксиметр", 1, null, 5);
        line.put("quantity", new BigDecimal("2.0005"));
        line.put("priceOverride", new BigDecimal("100.005"));
        body.putArray("items").add(line);
        JsonNode first = save(id, body).get("items").get(0);
        assertThat(first.get("quantity").decimalValue()).isEqualByComparingTo("2.001");
        assertThat(first.get("priceOverride").decimalValue()).isEqualByComparingTo("100.01");
        assertThat(first.get("calc").get("sum").decimalValue()).isEqualByComparingTo("200.12");   // 100,01 × 2,001
        Map<String, Object> row = jdbc.queryForMap("select quantity, price_override from client_offer_item where id = ?",
                first.get("id").asLong());
        assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo("2.001");
        assertThat((BigDecimal) row.get("price_override")).isEqualByComparingTo("100.01");
    }
}
