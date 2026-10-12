package com.vladoose.nir.privaterequest;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestRfqService;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Окно «Запросить цены» этапа 2 (спека 2026-10-12 §6.2): почта в окне, новый поставщик, клиент, документ. */
@SmtpIntegrationTest
class PrivateRequestRfqStage2Test {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestRfqService rfq;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired CompanyProfileRepository profileRepository;
    @Autowired EmailTemplateRepository templateRepository;

    // шаблон письма рынка правит оператор на «Шаблоне письма КП» (nirdb постоянная): тесты ждут зашитый — без него
    // своя строка о заказчике или тело без {{дедлайн}} уронили бы гейт без регресса (CLAUDE.md §14); откат — транзакцией теста
    @BeforeEach void kz() {
        MarketContext.set(Market.KZ);
        templateRepository.deleteAllInBatch();
    }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name, String email) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email(email).build());
    }

    private Tender request(String brand) {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Облучатель окна", brand, 2, null, null, null)), LinesMode.APPEND);
        return t;
    }

    private static List<Long> lotIds(Tender t) {
        return t.getLots().stream().map(TenderLot::getId).toList();
    }

    private static RfqSendRequest.Supplier existing(Distributor d, Tender t, String email, boolean disclose) {
        RfqSendRequest.Supplier s = new RfqSendRequest.Supplier();
        s.setDistributorId(d.getId());
        s.setEmail(email);
        s.setDiscloseClient(disclose);
        s.setLotIds(lotIds(t));
        return s;
    }

    private static RfqSendRequest.Supplier fresh(String name, Tender t, String email, boolean remember) {
        RfqSendRequest.NewSupplier n = new RfqSendRequest.NewSupplier();
        n.setName(name);
        n.setRememberBrands(remember);
        RfqSendRequest.Supplier s = new RfqSendRequest.Supplier();
        s.setNewSupplier(n);
        s.setEmail(email);
        s.setLotIds(lotIds(t));
        return s;
    }

    private static RfqSendRequest send(boolean withDocument, RfqSendRequest.Supplier... suppliers) {
        RfqSendRequest r = new RfqSendRequest();
        r.setWithDocument(withDocument);
        r.setSuppliers(List.of(suppliers));
        return r;
    }

    private static MimeMessage mailTo(String to) throws Exception {
        for (MimeMessage m : greenMail.getReceivedMessages()) {
            if (m.getAllRecipients()[0].toString().equals(to)) return m;
        }
        throw new AssertionError("нет письма для " + to);
    }

    private static String body(Part p) throws Exception {
        if (p.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return (String) p.getContent();
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = body(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static String document(Part p) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return docxText(p.getInputStream());
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = document(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static String docxText(InputStream in) throws Exception {
        try (XWPFDocument d = new XWPFDocument(in); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
            return x.getText().replace('\u00A0', ' ');
        }
    }

    @Test
    void typedEmailFillsEmptyCard_changedEmailOverridesWithoutTouchingCard() throws Exception {
        Tender t = request("ZZRFQ2A");
        Distributor empty = dist("ZZ Пустая карточка", null);
        Distributor full = dist("ZZ Полная карточка", "b@rfq2.kz");

        List<SendResult> r = rfq.send(t.getId(), send(false,
                existing(empty, t, "a@rfq2.kz", false), existing(full, t, "other@rfq2.kz", false)));

        assertThat(r).extracting(SendResult::emailSent).containsExactly(true, true);
        assertThat(distributorRepository.findById(empty.getId()).orElseThrow().getEmail()).isEqualTo("a@rfq2.kz");
        assertThat(priceRequestRepository.findById(r.get(0).priceRequestId()).orElseThrow().isEmailOverride()).isFalse();
        PriceRequest overridden = priceRequestRepository.findById(r.get(1).priceRequestId()).orElseThrow();
        assertThat(overridden.isEmailOverride()).isTrue();
        assertThat(overridden.getSentTo()).isEqualTo("other@rfq2.kz");
        assertThat(distributorRepository.findById(full.getId()).orElseThrow().getEmail()).isEqualTo("b@rfq2.kz");
        mailTo("a@rfq2.kz");
        mailTo("other@rfq2.kz");
    }

    @Test
    void clearedEmailSavesRequestWithoutLetter() {
        Tender t = request(null);
        Distributor d = dist("ZZ Очищено", "c@rfq2.kz");
        SendResult r = rfq.send(t.getId(), send(false, existing(d, t, "", false))).get(0);
        assertThat(r.emailSent()).isFalse();
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().getLetterStatus())
                .isEqualTo(LetterStatus.NO_EMAIL);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(distributorRepository.findById(d.getId()).orElseThrow().getEmail()).isEqualTo("c@rfq2.kz");
    }

    @Test
    void newSupplierIsCreatedWithRememberedBrands() throws Exception {
        Tender t = request("ZZBRANDNEW");
        String name = "ZZ Новый поставщик " + System.nanoTime();

        SendResult r = rfq.send(t.getId(), send(false, fresh(name, t, "n@rfq2.kz", true))).get(0);

        Distributor created = distributorRepository.findById(r.distributorId()).orElseThrow();
        assertThat(created.getName()).isEqualTo(name);
        assertThat(created.getMarket()).isEqualTo(Market.KZ);
        assertThat(created.getEmail()).isEqualTo("n@rfq2.kz");
        assertThat(created.getBrands()).containsExactly("ZZBRANDNEW");
        assertThat(r.emailSent()).isTrue();
        mailTo("n@rfq2.kz");
    }

    @Test
    void newSupplierNameOnOtherMarketIs400_sameMarketNameIsReused() {
        Tender t = request(null);
        MarketContext.set(Market.RF);
        Distributor rf = distributorRepository.save(Distributor.builder().name("ZZ Занято РФ " + System.nanoTime()).build());
        MarketContext.set(Market.KZ);
        assertThatThrownBy(() -> rfq.send(t.getId(), send(false, fresh(rf.getName(), t, "x@rfq2.kz", false))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже есть на другом рынке");

        Distributor kz = dist("ZZ Есть KZ", "kz@rfq2.kz");
        long before = distributorRepository.count();
        SendResult r = rfq.send(t.getId(), send(false, fresh(kz.getName().toLowerCase(), t, null, false))).get(0);
        assertThat(r.distributorId()).isEqualTo(kz.getId());
        assertThat(distributorRepository.count()).isEqualTo(before);
    }

    @Test
    void clientOnlyForTheCheckedSupplier_inLetterAndDocument() throws Exception {
        Tender t = request(null);
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Клиника Секретная");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Контакт Секретный");
        service.updateField(t.getId(), PrivateRequestField.DELIVERY_ADDRESS, "г. Уральск");
        Distributor told = dist("ZZ Доверенный", "told@rfq2.kz");
        Distributor silent = dist("ZZ Обычный", "silent@rfq2.kz");

        rfq.send(t.getId(), send(true, existing(told, t, null, true), existing(silent, t, null, false)));

        MimeMessage toTold = mailTo("told@rfq2.kz");
        assertThat(body(toTold)).contains("Конечный заказчик: ZZ Клиника Секретная, место поставки: г. Уральск")
                .doesNotContain("ZZ Контакт Секретный").doesNotContain(t.getTenderNumber());
        assertThat(document(toTold)).contains("Конечный заказчик: ZZ Клиника Секретная")
                .doesNotContain("ZZ Контакт Секретный").doesNotContain(t.getTenderNumber());
        MimeMessage toSilent = mailTo("silent@rfq2.kz");
        assertThat(body(toSilent)).doesNotContain("ZZ Клиника Секретная").doesNotContain(t.getTenderNumber());
        assertThat(document(toSilent)).doesNotContain("ZZ Клиника Секретная").doesNotContain(t.getTenderNumber());
    }

    @Test
    void discloseWithoutClientIs400() {
        Tender t = request(null);
        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Только контакт");
        Distributor d = dist("ZZ Раскрыть", "r@rfq2.kz");
        assertThatThrownBy(() -> rfq.send(t.getId(), send(false, existing(d, t, null, true))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Клиент в заявке не указан");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void invalidTypedAddressIs400BeforeAnyLetterOrRequest() {
        Tender t = request(null);
        Distributor ok = dist("ZZ Хороший", "ok@rfq2.kz");
        Distributor bad = dist("ZZ Плохой", "bad@rfq2.kz");
        long before = priceRequestRepository.count();
        assertThatThrownBy(() -> rfq.send(t.getId(), send(false,
                existing(ok, t, "ok@rfq2.kz", false), existing(bad, t, "bad@rfq2", false))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("«bad@rfq2»");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(priceRequestRepository.count()).isEqualTo(before);
    }

    @Test
    void severalTypedAddressesAllReceive() {
        Tender t = request(null);
        Distributor d = dist("ZZ Два отдела", null);
        rfq.send(t.getId(), send(false, existing(d, t, "a1@rfq2.kz; a2@rfq2.kz", false)));
        assertThat(greenMail.getReceivedMessages()).hasSize(2);
        assertThat(distributorRepository.findById(d.getId()).orElseThrow().getEmail()).isEqualTo("a1@rfq2.kz, a2@rfq2.kz");
    }

    @Test
    void documentBeforeSendingHasDashNumber_newSupplierNameInRecipient() throws Exception {
        Tender t = request(null);
        RfqPreviewRequest p = new RfqPreviewRequest();
        p.setNewSupplierName("ZZ Ещё не заведён");
        p.setLotIds(lotIds(t));
        p.setWithDocument(true);

        var file = rfq.document(t.getId(), p);

        assertThat(file.fileName()).startsWith("Запрос КП № — от ").endsWith(".docx");
        assertThat(docxText(new ByteArrayInputStream(file.bytes()))).contains("Исх. № —").contains("ZZ Ещё не заведён")
                .contains("ZZ Облучатель окна");
        assertThat(priceRequestRepository.findAll()).noneMatch(pr -> pr.getTender().getId().equals(t.getId()));
    }

    @Test
    void stampWithoutLoadedStampIs400() {
        // предусловие — в откатываемой транзакции теста: строку реквизитов правит оператор (CLAUDE.md §14)
        CompanyProfile p = profileRepository.findByMarket(Market.KZ).orElseThrow();
        p.setStampPng(null);
        profileRepository.saveAndFlush(p);
        Tender t = request(null);
        Distributor d = dist("ZZ Печать", "st@rfq2.kz");
        RfqSendRequest r = send(true, existing(d, t, null, false));
        r.setWithStamp(true);
        long before = priceRequestRepository.count();

        assertThatThrownBy(() -> rfq.send(t.getId(), r)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Печать не загружена");
        assertThat(priceRequestRepository.count()).isEqualTo(before);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }
}
