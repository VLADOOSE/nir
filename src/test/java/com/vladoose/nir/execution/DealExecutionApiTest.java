package com.vladoose.nir.execution;

import com.jayway.jsonpath.JsonPath;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API исполнения (спека 2026-10-19-deal-execution §9): права, рынок и вид сделки, тексты 400, вид раздела, файлы. */
@SpringBootTest
@Transactional
class DealExecutionApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired TenderRepository tenders;
    @Autowired EntityManager em;
    MockMvc mvc;

    static final String TODAY = LocalDate.now(Market.KZ.zone()).toString();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static RequestPostProcessor admin() { return user("admin").roles("ADMIN"); }
    private static RequestPostProcessor operator() { return user("op").roles("OPERATOR"); }
    private static String base(Tender t) { return "/api/private-requests/" + t.getId() + "/execution"; }

    /** Запрос MockMvc чистит MarketContext своего потока (§14) — рынок ставится заново перед вызовами сервисов. */
    private Tender acceptedDeal() {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        Distributor d = distributors.save(Distributor.builder().name("ZZ API " + System.nanoTime()).build());
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "3070455", 481), d.getId(), "100", "1"));
        em.flush();
        MarketContext.clear();
        return t;
    }

    private Distributor distributor() {
        MarketContext.set(Market.KZ);
        Distributor d = distributors.save(Distributor.builder().name("ZZ API ручной " + System.nanoTime()).build());
        em.flush();
        MarketContext.clear();
        return d;
    }

    @Test
    void viewCarriesExecution_patchReturnsViewWithProgress() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(get("/api/private-requests/" + t.getId()).header("X-Market", "KZ").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.visible").value(true))
                .andExpect(jsonPath("$.execution.offerLabel").value("КП № 481"))
                .andExpect(jsonPath("$.execution.amount").value(3070455.0))
                .andExpect(jsonPath("$.execution.amountFromOffer").value(true))
                .andExpect(jsonPath("$.execution.suppliers[0].fromOffer").value(true))
                .andExpect(jsonPath("$.execution.suppliers[0].amount").value(100.0));
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"CONTRACT_NUMBER\",\"value\":\"42/2026\"}")
                        .header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.contractNumber").value("42/2026"))
                .andExpect(jsonPath("$.progress.next.action").value("EXEC_PAY_CLIENT"))
                .andExpect(jsonPath("$.stage").value("EXEC_PREPAY"));
    }

    @Test
    void operatorReadsButCannotWrite() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"AMOUNT\",\"value\":\"1\"}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(put(base(t) + "/marks/DELIVERY").contentType(APPLICATION_JSON).content("{\"on\":\"" + TODAY + "\"}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(post(base(t) + "/payments").contentType(APPLICATION_JSON).content("{\"amount\":1,\"paidOn\":\"" + TODAY + "\"}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(post(base(t) + "/suppliers").contentType(APPLICATION_JSON).content("{\"distributorId\":1}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(get(base(t) + "/file-candidates").header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(multipart(base(t) + "/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1}))
                .param("stage", "CONTRACT").header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
    }

    @Test
    void otherMarketAndPublicTender404() throws Exception {
        Tender t = acceptedDeal();
        String body = "{\"field\":\"CONTRACT_NUMBER\",\"value\":\"1\"}";
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content(body).header("X-Market", "RF").with(admin()))
                .andExpect(status().isNotFound());
        MarketContext.set(Market.KZ);
        Tender pub = tenders.save(Tender.builder().tenderNumber("ZZ-PUB-" + System.nanoTime()).status("ACTIVE")
                .source(Source.PUBLIC_TENDER).build());
        em.flush();
        MarketContext.clear();
        mvc.perform(patch(base(pub)).contentType(APPLICATION_JSON).content(body).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void russianTextsFor400() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"AMOUNT\",\"value\":\"-5\"}")
                        .header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Сумма сделки: сумма должна быть больше нуля"));
        mvc.perform(put(base(t) + "/marks/XYZ").contentType(APPLICATION_JSON).content("{}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Неизвестная отметка"));
        String tomorrow = LocalDate.now(Market.KZ.zone()).plusDays(1).toString();
        mvc.perform(post(base(t) + "/payments").contentType(APPLICATION_JSON).content("{\"amount\":100,\"paidOn\":\"" + tomorrow + "\"}")
                        .header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Дата платежа: дата ещё не наступила — отмечаются только факты"));
    }

    @Test
    void paymentsSuppliersMarks() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(post(base(t) + "/payments").contentType(APPLICATION_JSON)
                        .content("{\"amount\":1000,\"paidOn\":\"" + TODAY + "\",\"note\":\"предоплата\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.clientPaid").value(1000.0))
                .andExpect(jsonPath("$.execution.clientPayments[0].note").value("предоплата"));
        Distributor rm = distributor();
        mvc.perform(post(base(t) + "/suppliers").contentType(APPLICATION_JSON)
                        .content("{\"distributorId\":" + rm.getId() + ",\"amount\":277025,\"currency\":\"RUB\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.suppliers[1].manual").value(true))
                .andExpect(jsonPath("$.execution.suppliers[1].currency").value("RUB"));
        mvc.perform(put(base(t) + "/suppliers/" + rm.getId() + "/marks/ORDER").contentType(APPLICATION_JSON)
                        .content("{\"on\":\"" + TODAY + "\",\"note\":\"№ 7\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.suppliers[1].orderNote").value("№ 7"));
        mvc.perform(put(base(t) + "/marks/DELIVERY").contentType(APPLICATION_JSON)
                        .content("{\"on\":\"" + TODAY + "\",\"note\":\"обучение\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.deliveryNote").value("обучение"));
        mvc.perform(delete(base(t) + "/suppliers/" + rm.getId()).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.suppliers.length()").value(1));
    }

    @Test
    void fileUploadDownloadDelete() throws Exception {
        Tender t = acceptedDeal();
        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        String body = mvc.perform(multipart(base(t) + "/files")
                        .file(new MockMultipartFile("file", "Договор 42.pdf", "application/pdf", pdf))
                        .param("stage", "CONTRACT").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.files[0].name").value("Договор 42.pdf"))
                .andExpect(jsonPath("$.execution.files[0].stage").value("CONTRACT"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fileId = JsonPath.<Integer>read(body, "$.execution.files[0].id").longValue();
        mvc.perform(get(base(t) + "/files/" + fileId).header("X-Market", "KZ").with(operator()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(content().bytes(pdf));
        mvc.perform(multipart(base(t) + "/files").file(new MockMultipartFile("file", "x.pdf", "application/pdf", pdf))
                        .param("stage", "NOWHERE").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Неизвестное место файла"));
        mvc.perform(delete(base(t) + "/files/" + fileId).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.files.length()").value(0));
    }

    @Test
    void revertedOfferKeepsSection() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"CONTRACT_NUMBER\",\"value\":\"42\"}")
                .header("X-Market", "KZ").with(admin())).andExpect(status().isOk());
        MarketContext.set(Market.KZ);
        ClientOffer o = offers.findByTenderIdOrderByIdDesc(t.getId()).get(0);
        o.setStatus(ClientOfferStatus.SENT);
        em.flush();
        MarketContext.clear();
        mvc.perform(get("/api/private-requests/" + t.getId()).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("KP_SENT"))
                .andExpect(jsonPath("$.execution.contractNumber").value("42"))
                .andExpect(jsonPath("$.execution.offerLabel").value(nullValue()));
    }
}
