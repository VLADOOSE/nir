package com.vladoose.nir.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.MailReceiveService;
import com.vladoose.nir.util.KpToken;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "mail.imap.enabled=true",
        "mail.imap.host=127.0.0.1",
        "mail.imap.port=3143",
        "mail.imap.username=zakup@westmed.kz",
        "mail.imap.password=secret",
        "mail.imap.protocol=imap",
        "mail.imap.market=KZ"
})
class MailReceiveServiceIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    @Autowired MailReceiveService mailReceiveService;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired InboundEmailRepository inboundEmailRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired DistributorRepository distributorRepository;

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
        mailReceiveService.poll();   // повторный опрос: письма прочитаны (SEEN) → не задваивает
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
}
