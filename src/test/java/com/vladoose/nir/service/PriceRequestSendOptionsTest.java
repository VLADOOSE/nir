package com.vladoose.nir.service;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.clientoffer.KpTestSupportAccess;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.PriceRequestController;
import com.vladoose.nir.dto.request.PriceRequestSendRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Настройки запроса в едином канале (спека 2026-10-12 §6.2–§6.6) — сквозь GreenMail. */
@SmtpIntegrationTest
class PriceRequestSendOptionsTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired PriceRequestSendService sendService;
    @Autowired PriceRequestController controller;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired CompanyProfileRepository profileRepository;

    Tender tender;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tender = tenderRepository.save(Tender.builder().tenderNumber("ZZ-OPT-1").status("ACTIVE")
                .source(Source.PUBLIC_TENDER).build());
        lot = tenderLotRepository.save(TenderLot.builder().tender(tender).lotNumber(1).equipName("ZZ Облучатель")
                .quantity(2).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String email) {
        return distributorRepository.save(Distributor.builder().name("ZZ Опции " + System.nanoTime()).email(email).build());
    }

    private List<SendItem> items() {
        return List.of(new SendItem(lot.getId(), null, 2));
    }

    private List<SendResult> send(Distributor d, SendOptions o) {
        return sendService.send(tender.getId(), List.of(d.getId()), items(), null, null, o);
    }

    /** Печать и подпись рынка — предусловие в откатываемой транзакции теста (строку правит оператор, CLAUDE.md §14). */
    private void stamp(byte[] png) {
        CompanyProfile p = profileRepository.findByMarket(Market.KZ).orElseThrow();
        p.setStampPng(png);
        p.setSignaturePng(png);
        profileRepository.saveAndFlush(p);
    }

    static String text(Part p) throws Exception {
        if (p.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return (String) p.getContent();
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = text(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    static Part attachment(Part p) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return p;
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                Part a = attachment(mp.getBodyPart(i));
                if (a != null) return a;
            }
        }
        return null;
    }

    static int anchors(Part docxPart) throws Exception {
        try (XWPFDocument d = new XWPFDocument(docxPart.getInputStream())) {
            List<XWPFParagraph> all = new ArrayList<>(d.getParagraphs());
            for (XWPFTable t : d.getTables()) {
                for (XWPFTableRow r : t.getRows()) for (XWPFTableCell c : r.getTableCells()) all.addAll(c.getParagraphs());
            }
            int n = 0;
            for (XWPFParagraph p : all) {
                for (XWPFRun r : p.getRuns()) for (CTDrawing dr : r.getCTR().getDrawingList()) n += dr.sizeOfAnchorArray();
            }
            return n;
        }
    }

    @Test
    void documentIsAttachedAndMentioned() throws Exception {
        SendResult r = send(dist("doc@opt.kz"), new SendOptions(null, true, false, false, null)).get(0);

        assertThat(r.emailSent()).isTrue();
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(text(m)).contains("Запрос на бланке компании — во вложении.");
        Part a = attachment(m);
        assertThat(MimeUtility.decodeText(a.getFileName())).startsWith("Запрос КП № " + r.priceRequestId() + " от ").endsWith(".docx");
        assertThat(a.getContentType()).startsWith(RfqDocumentService.DOCX);
        try (XWPFDocument docx = new XWPFDocument(a.getInputStream()); XWPFWordExtractor x = new XWPFWordExtractor(docx)) {
            assertThat(x.getText()).contains("Исх. № " + r.priceRequestId()).contains("ZZ Облучатель").doesNotContain("ZZ-OPT-1");
        }
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().isWithDocument()).isTrue();
    }

    @Test
    void noDocumentByDefault() throws Exception {
        send(dist("plain@opt.kz"), SendOptions.NONE);
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getContent()).isInstanceOf(String.class);
        assertThat((String) m.getContent()).doesNotContain("во вложении");
    }

    @Test
    void severalCardAddressesAllGetTheLetter() {
        SendResult r = send(dist("a@opt.kz, b@opt.kz"), SendOptions.NONE).get(0);
        assertThat(greenMail.getReceivedMessages()).hasSize(2);
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().getSentTo()).isEqualTo("a@opt.kz, b@opt.kz");
    }

    @Test
    void typedAddressOverridesCard_resendGoesThereAgain() throws Exception {
        Distributor d = dist("card@opt.kz");
        SendResult r = send(d, new SendOptions(null, false, false, false, List.of("typed@opt.kz"))).get(0);

        PriceRequest pr = priceRequestRepository.findById(r.priceRequestId()).orElseThrow();
        assertThat(pr.isEmailOverride()).isTrue();
        assertThat(pr.getSentTo()).isEqualTo("typed@opt.kz");
        assertThat(distributorRepository.findById(d.getId()).orElseThrow().getEmail()).isEqualTo("card@opt.kz");

        greenMail.purgeEmailFromAllMailboxes();
        assertThat(sendService.resend(r.priceRequestId()).emailSent()).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(greenMail.getReceivedMessages()[0].getAllRecipients()[0].toString()).isEqualTo("typed@opt.kz");
    }

    @Test
    void resendRepeatsSettingsAndUsesTheFixedCardAddress() throws Exception {
        Distributor d = dist("old@opt.kz");
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(2);
        SendResult r = send(d, new SendOptions(replyBy, true, false, false, null)).get(0);
        d.setEmail("fixed@opt.kz");
        distributorRepository.saveAndFlush(d);
        greenMail.purgeEmailFromAllMailboxes();

        assertThat(sendService.resend(r.priceRequestId()).emailSent()).isTrue();

        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("fixed@opt.kz");
        assertThat(text(m)).contains("Просим ответить до " + DATE.format(replyBy)).contains("во вложении");
        assertThat(attachment(m)).isNotNull();
    }

    @Test
    void stampWithoutLoadedStampIs400BeforeAnythingIsSaved() {
        stamp(null);
        long before = priceRequestRepository.count();
        Distributor d = dist("s@opt.kz");
        assertThatThrownBy(() -> send(d, new SendOptions(null, true, true, false, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Печать не загружена");
        assertThat(priceRequestRepository.count()).isEqualTo(before);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void resendAfterStampDeletedSendsDocumentWithoutStamp() throws Exception {
        stamp(KpTestSupportAccess.circlePng());
        SendResult r = send(dist("st@opt.kz"), new SendOptions(null, true, true, false, null)).get(0);
        assertThat(anchors(attachment(greenMail.getReceivedMessages()[0]))).isEqualTo(2);

        stamp(null);
        greenMail.purgeEmailFromAllMailboxes();
        assertThat(sendService.resend(r.priceRequestId()).emailSent()).isTrue();
        assertThat(anchors(attachment(greenMail.getReceivedMessages()[0]))).isZero();
    }

    @Test
    void stampWithoutDocumentIsIgnored() {
        stamp(null);   // печати нет, но и документа нет — отметка печати ни на что не влияет
        SendResult r = send(dist("nd@opt.kz"), new SendOptions(null, false, true, false, null)).get(0);
        assertThat(r.emailSent()).isTrue();
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().isWithStamp()).isFalse();
    }

    @Test
    void malformedCardEmailIsFailedNotCrash() {
        SendResult r = send(dist("broken@kz"), SendOptions.NONE).get(0);
        assertThat(r.emailSent()).isFalse();
        assertThat(r.reason()).isEqualTo(PriceRequestSendService.REASON_SEND_FAILED);
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().getLetterStatus())
                .isEqualTo(LetterStatus.FAILED);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void smtpFailureKeepsTheAttemptedAddress() {
        Distributor d = dist("c@opt.kz");
        greenMail.stop();
        try {
            SendResult r = send(d, new SendOptions(null, false, false, false, List.of("typed2@opt.kz"))).get(0);
            assertThat(r.reason()).isEqualTo(PriceRequestSendService.REASON_SEND_FAILED);
            PriceRequest pr = priceRequestRepository.findById(r.priceRequestId()).orElseThrow();
            assertThat(pr.getLetterStatus()).isEqualTo(LetterStatus.FAILED);
            assertThat(pr.getSentTo()).isEqualTo("typed2@opt.kz");
            assertThat(pr.isEmailOverride()).isTrue();
        } finally {
            greenMail.start();
        }
    }

    @Test
    void pastReplyByIs400() {
        Distributor d = dist("p@opt.kz");
        assertThatThrownBy(() -> send(d, SendOptions.replyBy(LocalDate.now(Market.KZ.zone()).minusDays(1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже прошла");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void tenderPanelOptionsReachTheLetter() throws Exception {
        PriceRequestSendRequest req = new PriceRequestSendRequest();
        req.setTenderId(tender.getId());
        req.setDistributorIds(List.of(dist("panel@opt.kz").getId()));
        PriceRequestSendRequest.Item it = new PriceRequestSendRequest.Item();
        it.setTenderLotId(lot.getId());
        it.setRequestedQuantity(2);
        req.setItems(List.of(it));
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(3);
        req.setReplyBy(replyBy);
        req.setWithDocument(true);

        assertThat(controller.preview(req).getBody()).contains("во вложении").contains("Просим ответить до " + DATE.format(replyBy));
        controller.send(req);

        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(attachment(m)).isNotNull();
        assertThat(text(m)).contains("Просим ответить до " + DATE.format(replyBy));
    }
}
