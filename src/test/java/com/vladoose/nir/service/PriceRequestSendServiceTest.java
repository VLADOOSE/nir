package com.vladoose.nir.service;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.username=zakup-test@westmed.local",
        "spring.mail.password=secret"
})
class PriceRequestSendServiceTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("zakup-test@westmed.local", "secret"));

    @Autowired PriceRequestSendService sendService;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;

    Tender tender;
    TenderLot lot1, lot2;
    MedEquipment eq;
    Distributor withEmail, withoutEmail;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tender = new Tender();
        tender.setTenderNumber("ZZ-SEND-1");
        tender.setStatus("ACTIVE");
        tender.setDeadline(LocalDate.of(2026, 7, 20));
        tender.setSourceExtId("999111");
        tenderRepository.save(tender);

        lot1 = lot(1, "ZZ УЗИ", null);
        lot2 = lot(2, "ZZ ИВЛ", "Спека ИВЛ: поток не менее 60 л/мин");

        eq = new MedEquipment();
        eq.setName("ZZ SonoMax");
        eq.setManufact("Mindray");
        medEquipmentRepository.save(eq);

        withEmail = dist("ZZ Поставщик-1", "d1@x.kz");
        withoutEmail = dist("ZZ Поставщик-2", null);
    }

    private TenderLot lot(int n, String name, String spec) {
        TenderLot l = new TenderLot();
        l.setTender(tender);
        l.setLotNumber(n);
        l.setEquipName(name);
        l.setQuantity(2);
        l.setRequiredSpec(spec);
        return tenderLotRepository.save(l);
    }

    private Distributor dist(String name, String email) {
        Distributor d = new Distributor();
        d.setName(name);
        d.setEmail(email);
        return distributorRepository.save(d);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void sendsToEachDistributor_marksNoEmail_andDeliversMailWithToken() throws Exception {
        List<SendResult> results = sendService.send(
                tender.getId(),
                List.of(withEmail.getId(), withoutEmail.getId()),
                List.of(new SendItem(lot1.getId(), eq.getId(), 2),
                        new SendItem(lot2.getId(), null, 1)),
                null, null);

        assertThat(results).hasSize(2);
        SendResult ok = results.get(0);
        SendResult noMail = results.get(1);
        assertThat(ok.emailSent()).isTrue();
        assertThat(ok.reason()).isNull();
        assertThat(noMail.emailSent()).isFalse();
        assertThat(noMail.reason()).isEqualTo("NO_EMAIL");

        // обе записи КП созданы, SENT, KZ, по 2 позиции
        for (SendResult r : results) {
            PriceRequest pr = priceRequestRepository.findById(r.priceRequestId()).orElseThrow();
            assertThat(pr.getStatus()).isEqualTo("SENT");
            assertThat(pr.getSentAt()).isNotNull();
            assertThat(pr.getMarket()).isEqualTo(Market.KZ);
            assertThat(pr.getItems()).hasSize(2);
        }

        // письмо ушло ровно одно — тому, у кого есть email; тема несёт токен
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo("d1@x.kz");
        assertThat(messages[0].getSubject()).contains("[КП-" + ok.priceRequestId() + "]");
        String body = messages[0].getContent().toString();
        assertThat(body).contains("West-Med").contains("ZZ SonoMax (Mindray)").contains("Требования (из ТЗ)");
    }

    @Test
    void operatorOverride_stillGetsServerTokenInSubject_andUsesOverrideBody() throws Exception {
        // оператор задаёт свою тему БЕЗ токена и своё тело письма →
        // сервер обязан сам приклеить [КП-id] по pr.getId() (а не взять токен из override),
        // тело письма — операторское.
        List<SendResult> results = sendService.send(
                tender.getId(),
                List.of(withEmail.getId()),
                List.of(new SendItem(lot2.getId(), null, 1)),
                "СРОЧНО: КП по ИВЛ",
                "Тело от оператора");

        assertThat(results).hasSize(1);
        SendResult ok = results.get(0);
        assertThat(ok.emailSent()).isTrue();

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject())
                .contains("[КП-" + ok.priceRequestId() + "]")
                .contains("СРОЧНО");
        String body = messages[0].getContent().toString();
        assertThat(body).contains("Тело от оператора");
    }

    @Test
    void lotFromAnotherTenderRejected() {
        Tender other = new Tender();
        other.setTenderNumber("ZZ-SEND-2");
        other.setStatus("ACTIVE");
        tenderRepository.save(other);

        assertThatThrownBy(() -> sendService.send(
                other.getId(),
                List.of(withEmail.getId()),
                List.of(new SendItem(lot1.getId(), null, 1)),
                null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void invalidQuantityRejected() {
        assertThatThrownBy(() -> sendService.send(
                tender.getId(),
                List.of(withEmail.getId()),
                List.of(new SendItem(lot1.getId(), null, 0)),
                null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void emptyItemsRejected() {
        assertThatThrownBy(() -> sendService.send(tender.getId(), List.of(withEmail.getId()), List.of(), null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void tenderFromAnotherMarketRejected() {
        // тендер+лот другого рынка (RF); текущий рынок — KZ → send не должен видеть чужой тендер
        MarketContext.set(Market.RF);
        Tender rf = new Tender();
        rf.setTenderNumber("ZZ-SEND-RF");
        rf.setStatus("ACTIVE");
        tenderRepository.save(rf);
        TenderLot rfLot = new TenderLot();
        rfLot.setTender(rf);
        rfLot.setLotNumber(1);
        rfLot.setEquipName("ZZ RF лот");
        rfLot.setQuantity(1);
        tenderLotRepository.save(rfLot);

        MarketContext.set(Market.KZ);
        assertThatThrownBy(() -> sendService.send(
                rf.getId(),
                List.of(withEmail.getId()),
                List.of(new SendItem(rfLot.getId(), null, 1)),
                null, null))
                .isInstanceOf(NotFoundException.class);
    }
}
