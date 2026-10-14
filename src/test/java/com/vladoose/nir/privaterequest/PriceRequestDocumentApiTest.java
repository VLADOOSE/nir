package com.vladoose.nir.privaterequest;

import com.vladoose.nir.clientoffer.KpTestSupportAccess;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Документ запроса цен файлом — PDF (переслать в WhatsApp) или Word: сохранённого запроса (с номером) и до отправки
 * (окно «Запросить цены»). Читать сохранённый может любой вошедший, документ до отправки — только администратор.
 */
@SpringBootTest
@Transactional
class PriceRequestDocumentApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService service;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    MockMvc mvc;
    Tender t;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        MarketContext.set(Market.KZ);
        t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Аппарат файлом", "ZZFILEBRAND", 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Документ API " + System.nanoTime()).build());
        pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT")
                .letterStatus(LetterStatus.MANUAL).sentAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now())
                .withDocument(true).build());
        pr.getItems().add(itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(0))
                .requestedQuantity(1).build()));
        MarketContext.clear();
    }

    @AfterEach
    void clear() {
        MarketContext.clear();
    }

    private static String fileName(MockHttpServletResponse r) {
        String h = r.getHeader("Content-Disposition");
        assertThat(h).startsWith("attachment;");
        return URLDecoder.decode(h.replaceAll(".*filename\\*=UTF-8''", ""), StandardCharsets.UTF_8);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void savedRequestAsPdf_anyoneLoggedIn() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/api/price-requests/" + pr.getId() + "/document?format=pdf")
                        .header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();

        assertThat(fileName(r)).startsWith("Запрос КП № " + pr.getId() + " от ").endsWith(".pdf");
        assertThat(KpTestSupportAccess.pdfText(r.getContentAsByteArray())).contains("Исх. № " + pr.getId())
                .contains("ZZ Аппарат файлом (ZZFILEBRAND)");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void savedRequestAsWord() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/api/price-requests/" + pr.getId() + "/document?format=docx")
                        .header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andReturn().getResponse();

        assertThat(fileName(r)).endsWith(".docx");
        assertThat(new String(r.getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    /** Без format — Word: так шла старая кнопка «Скачать документ» (открытые до деплоя вкладки). */
    @Test
    void withoutFormatItIsWord() throws Exception {
        String docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/document").header("X-Market", "KZ")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", docx));
        String body = "{\"newSupplierName\":\"ZZ Новый Word\",\"lotIds\":[" + t.getLots().get(0).getId() + "]}";
        mvc.perform(post("/api/private-requests/" + t.getId() + "/rfq/document").header("X-Market", "KZ")
                        .contentType("application/json").content(body).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", docx));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void unknownFormatIs400() throws Exception {
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/document?format=xls").header("X-Market", "KZ"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void otherMarketIs404() throws Exception {
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/document?format=pdf").header("X-Market", "RF"))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftBeforeSendingAsPdf_adminOnly() throws Exception {
        String body = "{\"newSupplierName\":\"ZZ Новый PDF\",\"lotIds\":[" + t.getLots().get(0).getId() + "]}";

        mvc.perform(post("/api/private-requests/" + t.getId() + "/rfq/document?format=pdf").header("X-Market", "KZ")
                        .contentType("application/json").content(body).with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        MockHttpServletResponse r = mvc.perform(post("/api/private-requests/" + t.getId() + "/rfq/document?format=pdf")
                        .header("X-Market", "KZ").contentType("application/json").content(body)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse();

        assertThat(fileName(r)).startsWith("Запрос КП № – от ").endsWith(".pdf");
        assertThat(KpTestSupportAccess.pdfText(r.getContentAsByteArray())).contains("ZZ Новый PDF");
    }
}
