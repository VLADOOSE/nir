package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.mail.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class PriceListInboundTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired WebApplicationContext wac;
    @Autowired MailIngestWriter writer;
    @Autowired InboundEmailRepository inbound;
    @Autowired InboundEmailFileRepository inboundFiles;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired MailCursorRepository cursors;
    @Autowired com.vladoose.nir.service.supplierreply.SupplierReplyService replies;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void priceFiles_onlyOfficeAndPdf_priceLikeByNameOrSubject() throws Exception {
        byte[] docx = PriceListFiles.orionLikeDocx();
        ParsedMail m = TestMails.mail().subject("Обновление цен").attachments("Прайс ноябрь.docx", "фото.jpg", "КП.docx")
                .files(new MailFile("Прайс ноябрь.docx", DOCX, (long) docx.length, docx),
                        new MailFile("фото.jpg", "image/jpeg", 3L, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
                        new MailFile("КП.docx", DOCX, (long) docx.length, docx),
                        new MailFile("большой.pdf", "application/pdf", null, null))   // больше предела — без байтов
                .build();
        List<InboundEmailFile> files = MailIngestWriter.priceFiles(m);
        assertThat(files).extracting(InboundEmailFile::getFileName).containsExactly("Прайс ноябрь.docx", "КП.docx");
        assertThat(files).extracting(InboundEmailFile::isPriceLike).containsExactly(true, false);
        ParsedMail bySubject = TestMails.mail().subject("Прейскурант 2026").attachments("ноябрь.docx")
                .files(new MailFile("ноябрь.docx", DOCX, (long) docx.length, docx)).build();
        assertThat(MailIngestWriter.priceFiles(bySubject).get(0).isPriceLike()).isTrue();
    }

    @Test
    void unmatchedLetter_storesFiles_otherKindsDoNot() throws Exception {
        byte[] docx = PriceListFiles.orionLikeDocx();
        ParsedMail m = TestMails.mail().from("ZZ Завод <zz-prices@example.kz>").subject("Прайс-лист")
                .attachments("Прайс.docx").files(new MailFile("Прайс.docx", DOCX, (long) docx.length, docx)).build();
        MailIngestWriter.WriteResult r = writer.write(m, 1L);
        assertThat(inboundFiles.findInfoByInboundIds(List.of(r.inboundId()))).hasSize(1);
        ParsedMail kp = TestMails.mail().subject("Re: [КП-999999998] запрос").attachments("Прайс.docx")
                .files(new MailFile("Прайс.docx", DOCX, (long) docx.length, docx)).build();
        MailIngestWriter.WriteResult r2 = writer.write(kp, 1L);
        assertThat(inboundFiles.findInfoByInboundIds(List.of(r2.inboundId()))).isEmpty();      // ответ поставщика — свой путь
    }

    @Test
    void telegramLine_forPriceLikeLetter() {
        ParsedMail m = TestMails.mail().subject("Прайс").attachments("Прайс.pdf").build();
        Classification c = MailClassifier.classify(m, new ClassifierRules("zakup@westmed.kz", "info@westmed.kz", false));
        String text = MailNotificationComposer.compose(m, c, null, null, MailIngestWriter.PRICE_LINE,
                new ComposeContext("zakup@westmed.kz", Market.KZ, "", OffsetDateTime.now())).text();
        assertThat(text).contains("📒 Похоже на прайс-лист — «Занести как прайс» во «Входящих»");
    }

    @Test
    void fromInbound_supplierBySender_or400_otherMarket404() throws Exception {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Из письма");
        d.setEmail("sales@zz-zavod.kz, zz-prices@example.kz");
        distributors.saveAndFlush(d);
        InboundEmail e = inbound.saveAndFlush(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("ZZ <zz-prices@example.kz>")
                .subject("Прайс").receivedAt(OffsetDateTime.now()).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
        byte[] xlsx = PriceListFiles.axionLikeXlsx();
        InboundEmailFile f = inboundFiles.saveAndFlush(InboundEmailFile.builder().inboundEmailId(e.getId()).fileName("Прайс.xlsx")
                .mimeType("application/octet-stream").sizeBytes((long) xlsx.length).sha256("zz" + System.nanoTime()).data(xlsx)
                .priceLike(true).build());
        InboundEmail stranger = inbound.saveAndFlush(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("x@nowhere.kz")
                .subject("Прайс").receivedAt(OffsetDateTime.now()).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
        InboundEmailFile g = inboundFiles.saveAndFlush(InboundEmailFile.builder().inboundEmailId(stranger.getId()).fileName("Прайс.xlsx")
                .sizeBytes((long) xlsx.length).sha256("zz" + System.nanoTime()).data(xlsx).priceLike(true).build());
        MarketContext.clear();
        mvc.perform(get("/api/inbound").header("X-Market", "KZ"))
                .andExpect(jsonPath("$[?(@.id == " + e.getId() + ")].priceFiles[0].name").value("Прайс.xlsx"));
        mvc.perform(post("/api/price-lists/from-inbound/" + f.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.distributorId").value(d.getId()))
                .andExpect(jsonPath("$.rows.length()").value(4));
        mvc.perform(post("/api/price-lists/from-inbound/" + g.getId()).header("X-Market", "KZ"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Выберите поставщика")));
        mvc.perform(post("/api/price-lists/from-inbound/" + g.getId()).header("X-Market", "KZ").contentType("application/json")
                .content("{\"distributorId\":" + d.getId() + "}")).andExpect(status().isOk());
        mvc.perform(post("/api/price-lists/from-inbound/" + f.getId()).header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/price-lists/counts").header("X-Market", "KZ"))
                .andExpect(jsonPath("$['" + d.getId() + "']").value(1));                // тот же файл — один прайс
    }

    @Test
    void fromInbound_twoSuppliersWithSenderAddress_400() throws Exception {
        for (String n : List.of("Двойник 1", "Двойник 2")) {
            Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, n);
            d.setEmail("twin@example.kz");
            distributors.saveAndFlush(d);
        }
        InboundEmail e = inbound.saveAndFlush(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("twin@example.kz")
                .subject("Прайс").receivedAt(OffsetDateTime.now()).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
        byte[] xlsx = PriceListFiles.axionLikeXlsx();
        InboundEmailFile f = inboundFiles.saveAndFlush(InboundEmailFile.builder().inboundEmailId(e.getId()).fileName("Прайс.xlsx")
                .sizeBytes((long) xlsx.length).sha256("zz" + System.nanoTime()).data(xlsx).priceLike(true).build());
        MarketContext.clear();
        mvc.perform(post("/api/price-lists/from-inbound/" + f.getId()).header("X-Market", "KZ"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Выберите поставщика")));
    }

    /** Строка «📒» — в уведомлении о письме «прочее», похожем на прайс (запись письма, а не только составитель). */
    @Test
    void writer_priceLikeLetter_lineInNotification() throws Exception {
        MailIngestWriter w = new MailIngestWriter(inbound, priceRequests, cursors,
                new com.vladoose.nir.integration.telegram.TelegramSettings(true, "http://127.0.0.1:1", "123:SECRET", "-1001", "77"),
                "zz-" + System.nanoTime() + "@test.kz", "zakup@westmed.kz", "info@westmed.kz", false, "https://ais.example", replies);
        w.setInboundFiles(inboundFiles);
        byte[] docx = PriceListFiles.orionLikeDocx();
        ParsedMail m = TestMails.mail().from("ZZ <zz-p@example.kz>").subject("Цены ноябрь").attachments("Прайс.docx")
                .files(new MailFile("Прайс.docx", DOCX, (long) docx.length, docx)).build();
        MailIngestWriter.WriteResult r = w.write(m, 1L);
        assertThat(inbound.findById(r.inboundId()).orElseThrow().getNotifyText()).contains(MailIngestWriter.PRICE_LINE);
        ParsedMail plain = TestMails.mail().from("ZZ <zz-p@example.kz>").subject("Привет").attachments("КП.docx")
                .files(new MailFile("КП.docx", DOCX, (long) docx.length, docx)).build();
        assertThat(inbound.findById(w.write(plain, 1L).inboundId()).orElseThrow().getNotifyText()).doesNotContain("📒");
    }
}
