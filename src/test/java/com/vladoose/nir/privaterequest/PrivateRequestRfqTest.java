package com.vladoose.nir.privaterequest;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LineRegistryAction;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.PriceRequestSendService;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestRfqService;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SmtpIntegrationTest
class PrivateRequestRfqTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestRfqService rfq;
    @Autowired PriceRequestSendService sendService;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired MedRegistryRepository registryRepository;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name, String email) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email(email).build());
    }

    private static List<TenderLot> sorted(Tender t) {
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
        return lots;
    }

    private static RfqSendRequest.Supplier supplier(Distributor d, TenderLot... lots) {
        RfqSendRequest.Supplier s = new RfqSendRequest.Supplier();
        s.setDistributorId(d.getId());
        s.setLotIds(Arrays.stream(lots).map(TenderLot::getId).toList());
        return s;
    }

    private static RfqSendRequest request(LocalDate replyBy, RfqSendRequest.Supplier... suppliers) {
        RfqSendRequest r = new RfqSendRequest();
        r.setReplyBy(replyBy);
        r.setSuppliers(List.of(suppliers));
        return r;
    }

    private static String bodyTo(MimeMessage[] mails, String to) throws Exception {
        for (MimeMessage m : mails) {
            if (m.getAllRecipients()[0].toString().equals(to)) return m.getContent().toString();
        }
        throw new AssertionError("нет письма для " + to);
    }

    @Test
    void eachSupplierGetsOneLetterWithOnlyItsLines_letterOutcomeIsStored() throws Exception {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Тонометр RFQ", "ZZRFQBRAND", 5, "упак", "Манжета 22–42 см", null),
                input("ZZ Кушетка RFQ", null, 2, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = sorted(t);
        Distributor a = dist("ZZ RFQ А", "a@rfq.kz");
        Distributor b = dist("ZZ RFQ Б", "b@rfq.kz");
        Distributor noMail = dist("ZZ RFQ без почты", null);
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(3);

        List<SendResult> results = rfq.send(t.getId(), request(replyBy,
                supplier(a, lots.get(0)), supplier(b, lots.get(0), lots.get(1)), supplier(noMail, lots.get(1))));

        assertThat(results).hasSize(3);
        assertThat(results).extracting(SendResult::emailSent).containsExactly(true, true, false);
        MimeMessage[] mails = greenMail.getReceivedMessages();
        assertThat(mails).hasSize(2);
        String bodyA = bodyTo(mails, "a@rfq.kz");
        assertThat(bodyA).contains("ZZ Тонометр RFQ (ZZRFQBRAND) — 5 упак")
                .contains("Требования: Манжета 22–42 см")
                .contains("Просим ответить до " + replyBy.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .doesNotContain("ZZ Кушетка RFQ")
                .doesNotContain(t.getTenderNumber());
        assertThat(bodyTo(mails, "b@rfq.kz")).contains("ZZ Тонометр RFQ").contains("ZZ Кушетка RFQ — 2 шт.");

        PriceRequest prA = priceRequestRepository.findById(results.get(0).priceRequestId()).orElseThrow();
        assertThat(prA.getLetterStatus()).isEqualTo(LetterStatus.SENT);
        assertThat(prA.getSentTo()).isEqualTo("a@rfq.kz");
        assertThat(prA.getReplyBy()).isEqualTo(replyBy);
        assertThat(prA.getItems()).hasSize(1);
        PriceRequest prNo = priceRequestRepository.findById(results.get(2).priceRequestId()).orElseThrow();
        assertThat(prNo.getLetterStatus()).isEqualTo(LetterStatus.NO_EMAIL);
        assertThat(prNo.getSentTo()).isNull();
    }

    @Test
    void confirmedRegistrationGoesIntoLetter_previewMatchesLetter_resendKeepsReplyBy() throws Exception {
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-RFQ-RU-1").name("Аппарат ZZRFQMODEL Q5")
                .producer("ZZRFQVENDOR").country("ГЕРМАНИЯ").unlimited(true).build());
        registryRepository.flush();
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("Аппарат ZZRFQMODEL Q5", "ZZRFQVENDOR", 1, null, null, null)),
                LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, null);
        Distributor a = dist("ZZ RFQ РУ", "ru@rfq.kz");
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(1);

        RfqPreviewRequest pr = new RfqPreviewRequest();
        pr.setDistributorId(a.getId());
        pr.setLotIds(List.of(lot.getId()));
        pr.setReplyBy(replyBy);
        KpPreviewResponse preview = rfq.preview(t.getId(), pr);
        assertThat(preview.getBody()).contains("Аппарат ZZRFQMODEL Q5 (ZZRFQVENDOR), РУ № ZZ-RFQ-RU-1 — 1 шт.");
        assertThat(greenMail.getReceivedMessages()).isEmpty();

        List<SendResult> results = rfq.send(t.getId(), request(replyBy, supplier(a, lot)));
        String body = bodyTo(greenMail.getReceivedMessages(), "ru@rfq.kz");
        assertThat(body).contains("РУ № ZZ-RFQ-RU-1").contains("Просим ответить до");

        sendService.resend(results.get(0).priceRequestId());
        MimeMessage[] mails = greenMail.getReceivedMessages();
        assertThat(mails).hasSize(2);
        assertThat(mails[1].getContent().toString()).contains("Просим ответить до");
    }

    @Test
    void foreignLotPastReplyByOrNoLines_is400_nothingSent() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Своя", null, 1, null, null, null)), LinesMode.APPEND);
        Tender other = service.createEmpty();
        service.addLines(other.getId(), List.of(input("ZZ Чужая", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor a = dist("ZZ RFQ отказ", "x@rfq.kz");
        TenderLot own = t.getLots().get(0);
        TenderLot foreign = other.getLots().get(0);
        LocalDate ok = LocalDate.now(Market.KZ.zone()).plusDays(2);

        assertThatThrownBy(() -> rfq.send(t.getId(), request(ok, supplier(a, own), supplier(a, foreign))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("не из этой заявки");
        assertThatThrownBy(() -> rfq.send(t.getId(), request(LocalDate.now(Market.KZ.zone()).minusDays(1), supplier(a, own))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже прошла");
        assertThatThrownBy(() -> rfq.send(t.getId(), request(ok, supplier(a))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("не выбраны позиции");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(priceRequestRepository.findByTenderId(t.getId())).isEmpty();
    }
}
