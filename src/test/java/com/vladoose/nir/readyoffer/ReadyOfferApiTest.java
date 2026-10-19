package com.vladoose.nir.readyoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.readyoffer.ReadyOfferFixtures;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API готового КП (спека 2026-10-19 §6): права, рынок, файл, просмотр, ответ КП, журнал, заявка, лента. */
@SpringBootTest
@Transactional
class ReadyOfferApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DealTimelineService timeline;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender request() {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        MarketContext.clear();
        return t;
    }

    private MockMultipartHttpServletRequestBuilder attach(Tender t, String name, byte[] b) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/private-requests/" + t.getId() + "/offer-file")
                .file(new MockMultipartFile("file", name, "application/octet-stream", b))
                .param("status", "SENT").param("sentOn", "2026-10-15").header("X-Market", "KZ");
    }

    private long attachWord(Tender t) throws Exception {
        byte[] kp = ReadyOfferFixtures.fatherKp("24.09.2026", List.<String[]>of(new String[]{"Кушетка КМС-01", "2", "95 000", "190 000"}), "190 000");
        String body = mvc.perform(attach(t, "КП West-Med 24.09.2026.docx", kp).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("FILE"))
                .andExpect(jsonPath("$.file.name").value("КП West-Med 24.09.2026.docx"))
                .andExpect(jsonPath("$.file.fileTotal").value(190000.0))
                .andExpect(jsonPath("$.items[0].priceOverride").value(95000.0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.id").longValue();
    }

    @Test
    void operatorReadsButCannotWrite() throws Exception {
        Tender t = request();
        mvc.perform(get("/api/private-requests/" + t.getId() + "/offer-file/candidates").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk());
        mvc.perform(attach(t, "КП.docx", new byte[]{1}).with(user("op").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/private-requests/" + t.getId() + "/offer-hints/1/dismiss").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/private-requests/" + t.getId() + "/offer-hints/1/mark-sent").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void wordFile_downloadViewJournalRequestTimeline() throws Exception {
        Tender t = request();
        long id = attachWord(t);

        mvc.perform(get("/api/client-offers/" + id + "/file").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        mvc.perform(get("/api/client-offers/" + id + "/file/view").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("WORD"))
                .andExpect(jsonPath("$.tables[0].rows[0].cells[1]").value("Кушетка КМС-01"));
        mvc.perform(get("/api/client-offers").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].origin").value("FILE"));
        mvc.perform(get("/api/private-requests/" + t.getId()).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.offers[0].origin").value("FILE"));

        MarketContext.set(Market.KZ);
        assertThat(timeline.forPrivateRequest(t.getId(), null, 50).items()).anySatisfy(i ->
                assertThat(i.text()).isEqualTo("КП от 24.09.2026 занесено из файла «КП West-Med 24.09.2026.docx»"));
    }

    @Test
    void pdfPagesAndOtherMarket404() throws Exception {
        Tender t = request();
        String body = mvc.perform(attach(t, "КП.pdf", ReplyFixtures.pdfBytes(2)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.id").longValue();
        mvc.perform(get("/api/client-offers/" + id + "/file/pages/1").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/client-offers/" + id + "/file").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(get("/api/client-offers/" + id + "/file").header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/private-requests/" + t.getId() + "/offer-file/candidates").header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void badStatusOrDateIs400() throws Exception {
        Tender t = request();
        mvc.perform(multipart("/api/private-requests/" + t.getId() + "/offer-file")
                        .file(new MockMultipartFile("file", "КП.docx", null, new byte[]{1}))
                        .param("status", "WHATEVER").param("sentOn", "2026-10-15").header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/private-requests/" + t.getId() + "/offer-file")
                        .file(new MockMultipartFile("file", "КП.docx", null, new byte[]{1}))
                        .param("status", "SENT").param("sentOn", "15.10.2026").header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
