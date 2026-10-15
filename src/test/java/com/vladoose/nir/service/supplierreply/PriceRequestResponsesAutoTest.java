package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUT /api/price-requests/{id}/responses: ручное сохранение снимает 💡; строка файла меняется только явно. */
@SpringBootTest
@Transactional
class PriceRequestResponsesAutoTest {

    @Autowired WebApplicationContext wac;
    @Autowired SupplierReplyFileRepository files;
    @Autowired PriceRequestRepository requests;
    @Autowired PriceRequestItemRepository items;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired EntityManager em;
    MockMvc mvc;
    PriceRequest pr;
    PriceRequestItem item;
    SupplierReplyFile file;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        MarketContext.set(Market.KZ);
        pr = request();
        file = file(pr);
        item = pr.getItems().get(0);
        item.setResponseFileId(file.getId());
        item.setResponseFileRow(3);
        em.flush();
        MarketContext.clear();
    }

    /** Запрос цен с одной позицией и 💡-ценой 100. */
    PriceRequest request() {
        Tender t = Tender.builder().tenderNumber("ZZPUT-" + System.nanoTime()).status("NEW").source(Source.PRIVATE_REQUEST).build();
        t.getLots().add(TenderLot.builder().tender(t).equipName("ZZ Позиция").quantity(1).build());
        t = tenders.save(t);
        Distributor d = distributors.save(Distributor.builder().name("ZZPUT Дистр " + System.nanoTime()).build());
        PriceRequest p = PriceRequest.builder().tender(t).distributor(d).status("RESPONDED").build();
        p.getItems().add(PriceRequestItem.builder().priceRequest(p).tenderLot(t.getLots().get(0)).requestedQuantity(1)
                .responsePrice(new BigDecimal("100")).responseAuto(true).build());
        return requests.save(p);
    }

    SupplierReplyFile file(PriceRequest p) {
        return files.save(SupplierReplyFile.builder().priceRequestId(p.getId()).fileName("kp.xlsx").sha256("zz" + System.nanoTime())
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    PriceRequestItem reload() {
        em.flush();
        em.clear();
        return items.findById(item.getId()).orElseThrow();
    }

    void putBody(String body, int expected) throws Exception {
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/responses").header("X-Market", "KZ")
                        .contentType("application/json").content(body).with(user("admin").roles("ADMIN")))
                .andExpect(status().is(expected));
    }

    @Test
    void putWithoutFileFieldsKeepsLink() throws Exception {
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":120,\"responseNote\":\"после звонка\"}]", 200);
        PriceRequestItem back = reload();
        assertThat(back.isResponseAuto()).isFalse();
        assertThat(back.getResponsePrice()).isEqualByComparingTo("120");
        assertThat(back.getResponseFileId()).isEqualTo(file.getId());
        assertThat(back.getResponseFileRow()).isEqualTo(3);
    }

    @Test
    void pickedRowOfThisRequestIsLinked() throws Exception {
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":90,\"fileId\":" + file.getId() + ",\"fileRow\":5}]", 200);
        assertThat(reload().getResponseFileRow()).isEqualTo(5);
    }

    @Test
    void fileOfAnotherRequestIs400() throws Exception {
        MarketContext.set(Market.KZ);
        SupplierReplyFile other = file(request());
        em.flush();
        MarketContext.clear();
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":90,\"fileId\":" + other.getId() + ",\"fileRow\":1}]", 400);
    }

    @Test
    void clearFileRemovesLink() throws Exception {
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":null,\"clearFile\":true}]", 200);
        PriceRequestItem back = reload();
        assertThat(back.getResponseFileId()).isNull();
        assertThat(back.getResponsePrice()).isNull();
    }
}
