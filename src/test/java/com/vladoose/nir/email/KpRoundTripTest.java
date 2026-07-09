package com.vladoose.nir.email;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.MailReceiveService;
import com.vladoose.nir.util.KpToken;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip: тестовые КП шлются на zakup@westmed.kz самому себе. Приём НЕ должен
 * самопомечать такое «своё» письмо (From == адрес отправки spring.mail.username) как
 * ответ поставщика. Реальный ответ (другой From, тот же токен) → RESPONDED как прежде.
 *
 * Механика доставки/поллинга переиспользована из рабочего MailReceiveServiceIntegrationTest
 * (GreenMail IMAP :3143, @TestPropertySource + greenMail.setUser + user.deliver), а не
 * ReflectionTestUtils/SMTP_IMAP из наброска брифа. spring.mail.username задан адресом
 * отправки, чтобы поле sendFrom сервиса совпало с From «своего» письма.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "mail.imap.enabled=true",
        "mail.imap.host=127.0.0.1",
        "mail.imap.port=3143",
        "mail.imap.username=zakup@westmed.kz",
        "mail.imap.password=secret",
        "mail.imap.protocol=imap",
        "mail.imap.market=KZ",
        "spring.mail.username=zakup@westmed.kz"
})
class KpRoundTripTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    @Autowired MailReceiveService mailReceiveService;
    @Autowired TenderRepository tenderRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired DistributorRepository distributorRepository;

    @AfterEach
    void clear() { MarketContext.clear(); }

    private Long sentPr() {
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder()
                .tenderNumber("KZ-RT-" + System.nanoTime())
                .status("NEW").market(Market.KZ).source(Source.PUBLIC_TENDER).build());
        Distributor dist = distributorRepository.save(Distributor.builder()
                .name("KZ-RT-Дистр-" + System.nanoTime()).email("d@x.kz").build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder()
                .tender(t).distributor(dist).market(Market.KZ).status("SENT").build());
        return pr.getId();
    }

    private void deliver(String from, String subject) throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress(from));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        m.setSubject(subject, "UTF-8");
        m.setText("Ответ поставщика: цена 1000000 тг, срок 30 дней.", "UTF-8");
        m.saveChanges();
        user.deliver(m);
    }

    private void poll() {
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();
    }

    @Test
    void supplierReplyMarksResponded() throws Exception {
        Long id = sentPr();
        deliver("supplier@example.kz", "Re: " + KpToken.subjectToken(id) + " Запрос КП");
        poll();
        assertThat(priceRequestRepository.findById(id).orElseThrow().getStatus()).isEqualTo("RESPONDED");
    }

    @Test
    void ownEchoDoesNotSelfMarkResponded() throws Exception {
        Long id = sentPr();
        // письмо «от нас самих» (From == адрес отправки) с нашим же токеном
        deliver("zakup@westmed.kz", KpToken.subjectToken(id) + " Запрос КП по тендеру");
        poll();
        assertThat(priceRequestRepository.findById(id).orElseThrow().getStatus()).isEqualTo("SENT");
    }
}
