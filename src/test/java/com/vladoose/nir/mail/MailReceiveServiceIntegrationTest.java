package com.vladoose.nir.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramStubServer;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.MailReceiveService;
import com.vladoose.nir.service.mail.ImapTestSupport;
import com.vladoose.nir.service.mail.MailboxConnector;
import com.vladoose.nir.service.mail.MailboxSession;
import com.vladoose.nir.service.mail.TestMimes;
import com.vladoose.nir.util.KpToken;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MailIntegrationTest
class MailReceiveServiceIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(true);

    @Autowired MailReceiveService mailReceiveService;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired InboundEmailRepository inboundEmailRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired MailCursorRepository cursorRepository;
    @Autowired MailboxConnector mailboxConnector;
    @Autowired EntityManager em;

    /**
     * Курсор ящика удаляется в транзакции теста: живая проверка на этой базе могла оставить закоммиченную строку
     * курсора, и тогда проход пошёл бы от чужого UID (откат вернёт её после теста).
     */
    @BeforeEach
    void freshCursor() {
        cursorRepository.findById("zakup@westmed.kz").ifPresent(cursorRepository::delete);
        em.flush();
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void poll_matchesSupplierResponse_andQueuesClientExcel() throws Exception {
        MarketContext.set(Market.KZ);
        Facility fac = facilityRepository.save(Facility.builder().name("ZZMAIL Клиника").build());
        Distributor dist = distributorRepository.save(
                Distributor.builder().name("ZZMAIL Дистр").email("d@x.kz").build());
        Tender tender = tenderRepository.save(Tender.builder()
                .tenderNumber("ZZMAIL-T1").facility(fac).status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder()
                .tender(tender).distributor(dist).status("SENT").build());
        Long prId = pr.getId();

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(message("supplier@x.kz",
                "Re: Запрос КП " + KpToken.subjectToken(prId), "Наша цена 100000 тенге", null, null));
        user.deliver(message("clinic@x.kz",
                "Заявка на оборудование", "Прошу выставить КП", sampleXlsx(), "zayavka.xlsx"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        PriceRequest reloaded = priceRequestRepository.findById(prId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("RESPONDED");
        assertThat(reloaded.getResponseDate()).isNotNull();

        List<InboundEmail> all = inboundEmailRepository.findAll();
        assertThat(all).anyMatch(e -> e.getType() == InboundType.SUPPLIER_RESPONSE
                && prId.equals(e.getMatchedPriceRequestId()));
        InboundEmail client = all.stream()
                .filter(e -> e.getType() == InboundType.CLIENT_REQUEST).findFirst().orElseThrow();
        assertThat(client.getAttachment()).isNotNull();
        assertThat(client.getAttachmentName()).endsWith(".xlsx");
        assertThat(client.getMarket()).isEqualTo(Market.KZ);

        long count = inboundEmailRepository.count();
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();   // повторный опрос: курсор по UID → не задваивает
        assertThat(inboundEmailRepository.count()).isEqualTo(count);
    }

    @Test
    void poll_singleLotKp_autoFillsResponsePrice() throws Exception {
        MarketContext.set(Market.KZ);
        Facility fac = facilityRepository.save(Facility.builder().name("ZZPRICE Клиника").build());
        Distributor dist = distributorRepository.save(
                Distributor.builder().name("ZZPRICE Дистр").email("p@x.kz").build());
        Tender tender = Tender.builder()
                .tenderNumber("ZZPRICE-T1").facility(fac).status("NEW")
                .source(Source.PRIVATE_REQUEST).build();
        TenderLot lot = TenderLot.builder().tender(tender).equipName("Аппарат").quantity(1).build();
        tender.getLots().add(lot);
        tender = tenderRepository.save(tender);                 // cascade сохраняет лот
        TenderLot savedLot = tender.getLots().get(0);

        PriceRequest pr = PriceRequest.builder()
                .tender(tender).distributor(dist).status("SENT").build();
        pr.getItems().add(PriceRequestItem.builder()
                .priceRequest(pr).tenderLot(savedLot).requestedQuantity(1).build());
        pr = priceRequestRepository.save(pr);                   // cascade сохраняет item
        Long prId = pr.getId();

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(message("supplier@x.kz",
                "Re: КП " + KpToken.subjectToken(prId), "Цена 3 200 000 ₸, срок 3 недели", null, null));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        PriceRequest reloaded = priceRequestRepository.findById(prId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("RESPONDED");
        PriceRequestItem item = reloaded.getItems().get(0);
        assertThat(item.getResponsePrice()).isEqualByComparingTo("3200000");
        assertThat(item.getResponseNote()).containsIgnoringCase("распознан");
    }

    @Test
    void poll_supplierRefusal_marksDeclined_noInventedPrice() throws Exception {
        MarketContext.set(Market.KZ);
        Facility fac = facilityRepository.save(Facility.builder().name("ZZDECL Клиника").build());
        Distributor dist = distributorRepository.save(
                Distributor.builder().name("ZZDECL Дистр").email("decl@x.kz").build());
        Tender tender = Tender.builder()
                .tenderNumber("ZZDECL-T1").facility(fac).status("NEW")
                .source(Source.PUBLIC_TENDER).build();
        TenderLot lot = TenderLot.builder().tender(tender).equipName("Аппарат").quantity(1).build();
        tender.getLots().add(lot);
        tender = tenderRepository.save(tender);
        TenderLot savedLot = tender.getLots().get(0);

        PriceRequest pr = PriceRequest.builder()
                .tender(tender).distributor(dist).status("SENT").build();
        pr.getItems().add(PriceRequestItem.builder()
                .priceRequest(pr).tenderLot(savedLot).requestedQuantity(1).build());
        pr = priceRequestRepository.save(pr);
        Long prId = pr.getId();

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(message("supplier@x.kz",
                "Re: " + KpToken.subjectToken(prId) + " Запрос КП",
                "Добрый день, данную позицию мы не поставляем, к сожалению.", null, null));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        PriceRequest reloaded = priceRequestRepository.findById(prId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("DECLINED");
        assertThat(reloaded.getResponseDate()).isNotNull();
        assertThat(reloaded.getItems().get(0).getResponsePrice()).isNull();  // цена не выдумана
    }

    private MimeMessage message(String from, String subject, String body, byte[] attach, String name)
            throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        msg.setSubject(subject, "UTF-8");
        if (attach == null) {
            msg.setText(body, "UTF-8");
        } else {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, "UTF-8");
            MimeBodyPart filePart = new MimeBodyPart();
            filePart.setContent(attach, "application/octet-stream");
            filePart.setFileName(name);
            filePart.setDisposition(MimeBodyPart.ATTACHMENT);
            MimeMultipart mp = new MimeMultipart();
            mp.addBodyPart(textPart);
            mp.addBodyPart(filePart);
            msg.setContent(mp);
        }
        msg.saveChanges();
        return msg;
    }

    private byte[] sampleXlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Наименование");
            h.createCell(1).setCellValue("Производитель");
            h.createCell(2).setCellValue("Кол-во");
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("Аппарат УЗИ");
            r.createCell(1).setCellValue("Mindray");
            r.createCell(2).setCellValue(2);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Реальная структура письма от почтового клиента: вложенный multipart/alternative (text+html)
     *  + xlsx-вложение с MIME-закодированным кириллическим именем. Раньше → UNMATCHED. */
    @Test
    void poll_handlesNestedMultipart_withEncodedCyrillicFilename() throws Exception {
        MarketContext.set(Market.KZ);
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");

        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Прошу выставить КП по списку", "UTF-8");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<p>Прошу выставить КП по списку</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(textPart);
        alt.addBodyPart(htmlPart);
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);

        MimeBodyPart filePart = new MimeBodyPart();
        filePart.setContent(sampleXlsx(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        filePart.setFileName(MimeUtility.encodeText("Список ТХ Тлендиева.xlsx", "UTF-8", "B"));
        filePart.setDisposition(MimeBodyPart.ATTACHMENT);

        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(altPart);
        mixed.addBodyPart(filePart);

        MimeMessage msg = new MimeMessage((Session) null);
        msg.setFrom(new InternetAddress("clinic@x.kz"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        msg.setSubject("Заявка на оборудование", "UTF-8");
        msg.setContent(mixed);
        msg.saveChanges();
        user.deliver(msg);

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        InboundEmail client = inboundEmailRepository.findAll().stream()
                .filter(e -> e.getType() == InboundType.CLIENT_REQUEST).findFirst().orElseThrow();
        assertThat(client.getAttachment()).isNotNull();                       // вложение извлечено
        assertThat(client.getAttachmentName().toLowerCase()).endsWith(".xlsx");
        assertThat(client.getAttachmentName()).contains("Список");            // MIME-имя декодировано
        assertThat(client.getExcerpt()).contains("Прошу выставить КП");       // вложенный text/plain собран
    }

    /** Уведомления westmed.kz о заявках — дубли того, что приходит через API сайта (обращения). */
    @Test
    void poll_skipsWestmedSiteNotifications_butKeepsClientMail() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        String tag = "ZZSITE-" + System.nanoTime();
        user.deliver(message("WestMed.kz <info@westmed.kz>", "Запрос КП (2 поз.) — westmed.kz",
                "Запрос коммерческого предложения " + tag, null, null));
        user.deliver(message("clinic@x.kz", "Нужен облучатель " + tag, "Добрый день, нужен облучатель", null, null));

        MarketContext.set(Market.KZ);
        long before = inboundEmailRepository.count();
        PollResultResponse res = mailReceiveService.poll();

        assertThat(res.getSkippedSiteNotifications()).isEqualTo(1);
        assertThat(inboundEmailRepository.count()).isEqualTo(before + 1);   // сохранено только письмо клиники
        assertThat(inboundEmailRepository.findAll())
                .anyMatch(e -> ("Нужен облучатель " + tag).equals(e.getSubject()))
                .noneMatch(e -> e.getExcerpt() != null && e.getExcerpt().contains(tag)
                        && e.getSubject() != null && e.getSubject().endsWith("— westmed.kz"));
    }

    /**
     * U+0000 в теме, имени отправителя и тексте (RFC 2047 «=?UTF-8?B?…AA…?=») PostgreSQL в text не хранит (SQLSTATE
     * 22021): такое письмо не записывалось ни целиком, ни коротко, проход вставал на нём каждую минуту, и вся следующая
     * почта стояла — а прислать его может кто угодно. Символ вычищается при разборе: записаны оба письма, курсор прошёл оба.
     */
    @Test
    void nulCharacterLetter_writtenAndDoesNotStallMailbox() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        MimeMessage nul = TestMimes.plain("x@x.kz", "x", "Текст\0 с нулём");
        nul.setHeader("From", TestMimes.encodedWord("Иван\0 Петров") + " <nul@x.kz>");
        nul.setHeader("Subject", TestMimes.encodedWord("ZZNUL\0 тема"));
        user.deliver(nul);
        user.deliver(TestMimes.plain("s@x.kz", "ZZNUL следом", "текст"));

        MarketContext.set(Market.KZ);
        PollResultResponse res = mailReceiveService.poll();

        assertThat(res.isOk()).isTrue();
        assertThat(res.getFetched()).isEqualTo(2);
        assertThat(inboundEmailRepository.findAll())
                .anyMatch(e -> "ZZNUL тема".equals(e.getSubject()) && "Иван Петров <nul@x.kz>".equals(e.getFromAddress())
                        && e.getExcerpt() != null && e.getExcerpt().contains("Текст с нулём"))
                .anyMatch(e -> "ZZNUL следом".equals(e.getSubject()));
        long maxUid;
        try (MailboxSession s = mailboxConnector.open()) {
            maxUid = s.maxUid();
        }
        assertThat(cursorRepository.findById("zakup@westmed.kz").orElseThrow().getLastUid()).isEqualTo(maxUid);
    }

    @Test
    void poll_doesNotMarkLettersSeen() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.plain("s@x.kz", "ZZSEEN письмо", "текст"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        assertThat(ImapTestSupport.seen("ZZSEEN письмо")).isFalse();
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> "ZZSEEN письмо".equals(e.getSubject()));
    }

    @Test
    void firstRun_skipsOldLetter_thenCatchesUpOldDatedLetterAfterCursor() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        ImapTestSupport.appendWithReceivedDate("ZZOLD до включения", Instant.now().minus(Duration.ofHours(3)));
        user.deliver(TestMimes.plain("s@x.kz", "ZZFRESH свежее", "текст"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();                               // первый запуск: только окно 60 мин
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> "ZZFRESH свежее".equals(e.getSubject()))
                .noneMatch(e -> "ZZOLD до включения".equals(e.getSubject()));

        ImapTestSupport.appendWithReceivedDate("ZZGAP пришло во время простоя", Instant.now().minus(Duration.ofHours(2)));
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();                               // курсор есть: окно больше не действует
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> "ZZGAP пришло во время простоя".equals(e.getSubject()));
    }

    @Test
    void newUidValidity_doesNotDuplicate() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.plain("s@x.kz", "ZZUV письмо", "текст"));
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();
        long count = inboundEmailRepository.count();

        MailCursor c = cursorRepository.findById("zakup@westmed.kz").orElseThrow();
        long realValidity = c.getUidValidity();
        c.setUidValidity(realValidity + 1000);                  // как будто ящик пересоздан
        cursorRepository.save(c);
        em.flush();

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();                               // первый запуск снова, но Message-ID уже записан

        assertThat(inboundEmailRepository.count()).isEqualTo(count);
        assertThat(cursorRepository.findById("zakup@westmed.kz").orElseThrow().getUidValidity()).isEqualTo(realValidity);
    }

    @Test
    void bounceEndToEnd_keepsRequestSent() throws Exception {
        MarketContext.set(Market.KZ);
        Distributor dist = distributorRepository.save(Distributor.builder().name("ZZBNC Дистр " + System.nanoTime()).email("b@x.kz").build());
        Tender tender = tenderRepository.save(Tender.builder().tenderNumber("ZZBNC-T1").status("NEW").source(Source.PUBLIC_TENDER).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(tender).distributor(dist).status("SENT").build());

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.dsn(KpToken.subjectToken(pr.getId()) + " Запрос КП", "b@x.kz", "550 5.1.1 User unknown"));

        MarketContext.set(Market.KZ);
        PollResultResponse res = mailReceiveService.poll();

        assertThat(res.getBounces()).isEqualTo(1);
        assertThat(priceRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> e.getType() == InboundType.BOUNCE
                && pr.getId().equals(e.getMatchedPriceRequestId()));
    }

    @Test
    void autoReplyEndToEnd_keepsRequestSent() throws Exception {
        MarketContext.set(Market.KZ);
        Distributor dist = distributorRepository.save(Distributor.builder().name("ZZAUTO Дистр " + System.nanoTime()).email("a@x.kz").build());
        Tender tender = tenderRepository.save(Tender.builder().tenderNumber("ZZAUTO-T1").status("NEW").source(Source.PUBLIC_TENDER).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(tender).distributor(dist).status("SENT").build());

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.autoReply("a@x.kz", "Re: " + KpToken.subjectToken(pr.getId()) + " Запрос",
                "Я в отпуске до 12.10", "Auto-Submitted", "auto-replied"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        assertThat(priceRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> e.getType() == InboundType.AUTO_REPLY);
    }

    @Test
    void telegramEndToEnd_sendsToThread_marksSent() throws Exception {
        em.createNativeQuery("update inbound_email set notify_status = 'SENT' where notify_status = 'PENDING'").executeUpdate();
        try (TelegramStubServer stub = TelegramStubServer.start(7798)) {
            GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
            user.deliver(TestMimes.plain("Иван <ivan@x.kz>", "ZZTG Прайс октябрь", "Высылаем прайс"));

            MarketContext.set(Market.KZ);
            PollResultResponse res = mailReceiveService.poll();

            assertThat(res.getTelegramSent()).isEqualTo(1);
            assertThat(stub.requests()).hasSize(1);
            assertThat(stub.requests().get(0).path()).isEqualTo("/bot123456:TEST-TOKEN-SECRET/sendMessage");
            assertThat(stub.requests().get(0).body()).contains("\"message_thread_id\":77")
                    .contains("✉️ Письмо на zakup@westmed.kz · Иван <ivan@x.kz>")
                    .contains("https://ais.example/inbound?market=KZ");
            em.flush();
            em.clear();
            InboundEmail row = inboundEmailRepository.findAll().stream()
                    .filter(e -> "ZZTG Прайс октябрь".equals(e.getSubject())).findFirst().orElseThrow();
            assertThat(row.getNotifyStatus()).isEqualTo(NotifyStatus.SENT);
        }
    }
}
