package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static com.vladoose.nir.service.supplierreply.ReplyFixtures.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Экран «Ответ поставщика»: вид, гард рынка, права, загрузка, страницы PDF, скачивание, роли колонок, удаление. */
@SpringBootTest
@Transactional
class SupplierReplyApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired SupplierReplyService replies;
    MockMvc mvc;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(
                input("Кушетка медицинская смотровая", null, 2, null, null, null),
                input("Столик процедурный", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Ответ API " + System.nanoTime()).build());
        pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").sentAt(OffsetDateTime.now()).build());
        for (TenderLot l : t.getLots()) pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(l.getQuantity()).build()));
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    void ingest() {
        MarketContext.set(Market.KZ);
        ReplyTable table = new ReplyTable("Лист1", 4, 4, "KZT", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена, тг", LineField.PRICE), new ReplyTable.Column(3, "Сумма, тг", LineField.SUM)),
                List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "1", "95 000", "95 000")),
                        new ReplyTable.Row(6, List.of("Столик процедурный СП-02", "1", "48 500", "48 500")),
                        new ReplyTable.Row(7, List.of("Доставка", "1", "5 000", "5 000"))));
        replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП.xlsx", table)), null);
        MarketContext.clear();
    }

    @Test
    void viewShowsPositionsRowsFlagsAndTable() throws Exception {
        ingest();
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.supplierName", startsWith("ZZ Ответ API")))
                .andExpect(jsonPath("$.positions[0].auto").value(true))
                .andExpect(jsonPath("$.positions[0].row.fileRow").value(5))
                .andExpect(jsonPath("$.positions[0].flags", hasItem(startsWith("кол-во: 1"))))
                .andExpect(jsonPath("$.table.columns[2].role").value("PRICE"))
                .andExpect(jsonPath("$.counts.unverified").value(2))
                .andExpect(jsonPath("$.counts.unassignedRows").value(1))
                .andExpect(jsonPath("$.sources[0].type").value("FILE"));
    }

    @Test
    void otherMarketIs404_operatorCannotWrite() throws Exception {
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/price-requests/" + pr.getId() + "/verify").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadXlsx_appliesPrices_downloadIsAttachment() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, тг"),
                List.of("Кушетка медицинская смотровая КМС-01", "2", "95 000")));
        String body = mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП WhatsApp.xlsx", "application/octet-stream", xlsx))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].price").value(95000.0))
                .andExpect(jsonPath("$.sources[0].uploaded").value(true))
                .andReturn().getResponse().getContentAsString();
        Long fileId = com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.fileId").longValue();

        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        // файл чужого рынка не отдаётся: гард запроса стоит и у скачивания, не только у вида экрана
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void pdfPageIsPng_beyondCountIs404() throws Exception {
        String body = mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП.pdf", "application/pdf", pdfBytes(2)))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(body, "$.sources[0].fileId");
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/pages/1").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/pages/5").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void columnsPut_reappliesPrices() throws Exception {
        ingest();
        String view = mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(view, "$.fileId");
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/columns").header("X-Market", "KZ")
                        .contentType("application/json").content("[{\"index\":2,\"role\":\"IGNORE\"},{\"index\":3,\"role\":\"PRICE\"}]")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.columns[3].role").value("PRICE"));
    }

    @Test
    void emailFileDeleteIs400() throws Exception {
        ingest();
        String view = mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(view, "$.fileId");
        mvc.perform(delete("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
