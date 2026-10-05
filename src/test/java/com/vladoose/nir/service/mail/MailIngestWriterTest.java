package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.util.KpToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MailIngestWriterTest {

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired PriceRequestRepository prRepo;
    @Autowired MailCursorRepository cursorRepo;
    @Autowired TenderRepository tenderRepo;
    @Autowired DistributorRepository distributorRepo;

    static final TelegramSettings TG_ON = new TelegramSettings(true, "http://127.0.0.1:1", "123:SECRET", "-1001", "77");
    static final TelegramSettings TG_OFF = new TelegramSettings(false, "", "", "", "");

    final String box = "zz-" + System.nanoTime() + "@test.kz";

    MailIngestWriter writer(TelegramSettings tg, boolean clientRequests) {
        return new MailIngestWriter(inboundRepo, prRepo, cursorRepo, tg, box, "zakup@westmed.kz", "info@westmed.kz",
                clientRequests, "https://ais.example");
    }

    MailIngestWriter writer() { return writer(TG_ON, false); }

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Запрос КП: lots — число лотов (0 — без строк), source — тендер или частная заявка. */
    PriceRequest pr(String status, int lots, Source source) {
        Tender t = Tender.builder().tenderNumber("ZZW-" + System.nanoTime()).status("NEW").source(source).build();
        for (int i = 0; i < lots; i++) {
            t.getLots().add(TenderLot.builder().tender(t).equipName("Аппарат " + (i + 1)).quantity(1).build());
        }
        t = tenderRepo.save(t);
        Distributor d = distributorRepo.save(Distributor.builder().name("ZZW Дистр " + System.nanoTime()).email("sales@zzw.kz").build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status(status).build();
        for (TenderLot l : t.getLots()) {
            pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(1).build());
        }
        return prRepo.save(pr);
    }

    List<InboundEmail> rows() {
        return inboundRepo.findAll().stream().filter(e -> box.equals(e.getMailbox())).toList();
    }

    @Test
    void supplierResponse_singleLot_parsesPrice_queuesLoudNotification_movesCursor() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);

        MailIngestWriter.WriteResult r = writer().write(mail().uid(10).subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Цена 3 200 000 ₸, срок 3 недели").build(), 5);

        assertThat(r.mailClass()).isEqualTo(MailClass.SUPPLIER_RESPONSE);
        assertThat(r.outcome()).isEqualTo(KpOutcome.PRICE_PARSED);
        PriceRequest back = prRepo.findById(p.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("RESPONDED");
        assertThat(back.getItems().get(0).getResponsePrice()).isEqualByComparingTo("3200000");
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.SUPPLIER_RESPONSE);
        assertThat(e.getMatchedPriceRequestId()).isEqualTo(p.getId());
        assertThat(e.getImapUid()).isEqualTo(10L);
        assertThat(e.getMarket()).isEqualTo(Market.KZ);
        assertThat(e.getNotifyStatus()).isEqualTo(NotifyStatus.PENDING);
        assertThat(e.isNotifySilent()).isFalse();
        assertThat(e.getNotifyQueuedAt()).isNotNull();
        assertThat(e.getNotifyText()).startsWith("📩 Ответ поставщика · ZZW Дистр")
                .contains("💡 Цена распознана: 3\u00A0200\u00A0000,00 ₸")
                .contains("Запрос КП №" + p.getId() + " · тендер ZZW-")
                .endsWith("/tenders?openId=" + p.getTender().getId() + "&market=KZ");
        MailCursor c = cursorRepo.findById(box).orElseThrow();
        assertThat(c.getUidValidity()).isEqualTo(5);
        assertThat(c.getLastUid()).isEqualTo(10);
    }

    @Test
    void supplierRefusal_declined() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Добрый день, данную позицию мы не поставляем, к сожалению.").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.DECLINED);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("DECLINED");
        assertThat(rows().get(0).getNotifyText()).contains("⛔ Поставщик отказался");
    }

    @Test
    void multiLot_noAutoPrice() {
        PriceRequest p = pr("SENT", 2, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Цена 100 000 тенге").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.MULTI_LOT);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("RESPONDED");
    }

    @Test
    void alreadyAccepted_unchanged() {
        PriceRequest p = pr("ACCEPTED", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Уточнение: цена 5 000 тенге").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.UNCHANGED);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("ACCEPTED");
        assertThat(rows().get(0).getNotifyText()).contains("Повторное письмо — статус «Принят» не меняли");
    }

    @Test
    void tokenOfMissingRequest_notFound() {
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: [КП-999999999] Запрос").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.NOT_FOUND);
        assertThat(rows().get(0).getMatchedPriceRequestId()).isNull();
        assertThat(rows().get(0).getNotifyText()).contains("Запрос КП №999999999 в АИС не найден");
    }

    @Test
    void bounce_keepsRequestSent_loud() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().from("MAILER-DAEMON@corp.mail.ru")
                .subject("Undelivered Mail Returned to Sender")
                .contentType("multipart/report; report-type=delivery-status; boundary=x")
                .bounce("sales@zzw.kz", "5.1.1", "550 5.1.1 User unknown", KpToken.subjectToken(p.getId()) + " Запрос КП").build(), 5);

        assertThat(r.mailClass()).isEqualTo(MailClass.BOUNCE);
        PriceRequest back = prRepo.findById(p.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("SENT");
        assertThat(back.getResponseDate()).isNull();
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.BOUNCE);
        assertThat(e.getMatchedPriceRequestId()).isEqualTo(p.getId());
        assertThat(e.isNotifySilent()).isFalse();
        assertThat(e.getNotifyText()).startsWith("⚠️ Письмо не доставлено · ZZW Дистр").contains("(sales@zzw.kz)")
                .contains("Причина: 550 5.1.1 User unknown");
    }

    @Test
    void undeliverableSubjectFromPostmaster_isBounce_notResponded() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        writer().write(mail().from("postmaster@medtech.kz").subject("Undeliverable: " + KpToken.subjectToken(p.getId()) + " Запрос")
                .text("Delivery has failed to these recipients").build(), 5);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(rows().get(0).getType()).isEqualTo(InboundType.BOUNCE);
    }

    @Test
    void autoReply_keepsRequestSent_silent() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId())).header("Auto-Submitted", "auto-replied")
                .text("Я в отпуске до 12.10").build(), 5);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.AUTO_REPLY);
        assertThat(e.isNotifySilent()).isTrue();
    }

    @Test
    void excelWithoutToken_dependsOnClientRequests() {
        writer(TG_ON, false).write(mail().uid(1).excel("прайс.xlsx").build(), 5);
        InboundEmail priceList = rows().get(0);
        assertThat(priceList.getType()).isEqualTo(InboundType.UNMATCHED);
        assertThat(priceList.getAttachment()).isNull();

        writer(TG_ON, true).write(mail().uid(2).excel("заявка.xlsx").build(), 5);
        InboundEmail request = rows().stream().filter(e -> e.getImapUid() == 2L).findFirst().orElseThrow();
        assertThat(request.getType()).isEqualTo(InboundType.CLIENT_REQUEST);
        assertThat(request.getAttachment()).isNotNull();
        assertThat(request.getAttachmentName()).isEqualTo("заявка.xlsx");
    }

    @Test
    void duplicateMessageId_noSecondRow_cursorMoves() {
        writer().write(mail().uid(1).messageId("<same@x.kz>").build(), 5);
        MailIngestWriter.WriteResult r = writer().write(mail().uid(2).messageId("<same@x.kz>").build(), 5);
        assertThat(r.duplicate()).isTrue();
        assertThat(rows()).hasSize(1);
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(2);
    }

    /**
     * Пустой заголовок Message-ID (или {@code <>}) — как отсутствующий: иначе все такие письма после первого ушли бы
     * в «дубль» без строки и уведомления.
     */
    @Test
    void blankMessageId_notADuplicate() {
        writer().write(mail().uid(1).messageId("").build(), 5);
        MailIngestWriter.WriteResult r = writer().write(mail().uid(2).messageId("").build(), 5);
        MailIngestWriter.WriteResult r2 = writer().write(mail().uid(3).messageId("<>").build(), 5);
        MailIngestWriter.WriteResult r3 = writer().write(mail().uid(4).messageId("<>").build(), 5);
        assertThat(r.duplicate()).isFalse();
        assertThat(r2.duplicate()).isFalse();
        assertThat(r3.duplicate()).isFalse();
        assertThat(rows()).hasSize(4).allSatisfy(e -> assertThat(e.getMessageId()).isNull());
    }

    @Test
    void siteNotification_noRow_cursorMoves() {
        MailIngestWriter.WriteResult r = writer().write(mail().uid(3).from("WestMed.kz <info@westmed.kz>")
                .subject("Запрос КП (2 поз.) — westmed.kz").build(), 5);
        assertThat(r.mailClass()).isEqualTo(MailClass.SITE_NOTIFICATION);
        assertThat(rows()).isEmpty();
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(3);
    }

    @Test
    void ownEcho_rowWithoutNotification() {
        writer().write(mail().from("zakup@westmed.kz").subject("[КП-5] Запрос").build(), 5);
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.UNMATCHED);
        assertThat(e.getNotifyStatus()).isNull();
    }

    @Test
    void telegramOff_noQueue() {
        writer(TG_OFF, false).write(mail().build(), 5);
        assertThat(rows().get(0).getNotifyStatus()).isNull();
        assertThat(rows().get(0).getNotifyText()).isNull();
    }

    @Test
    void writeBroken_shortRowAndSilentNotice() {
        writer().writeBroken(new BrokenMail(9, "<b@x>", "a@b.kz", "Тема", java.time.OffsetDateTime.now(), "ParseException"), 5);
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.UNMATCHED);
        assertThat(e.getExcerpt()).isEqualTo("Письмо не удалось разобрать (ParseException) — откройте его в почте");
        assertThat(e.isNotifySilent()).isTrue();
        assertThat(e.getNotifyText()).contains("Письмо не удалось разобрать");
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(9);
    }

    @Test
    void cursor_neverBackwardsWithinValidity_resetsOnNewValidity() {
        MailIngestWriter w = writer();
        w.moveCursorTo(5, 100);
        w.moveCursorTo(5, 40);
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(100);
        w.moveCursorTo(6, 3);
        MailCursor c = cursorRepo.findById(box).orElseThrow();
        assertThat(c.getUidValidity()).isEqualTo(6);
        assertThat(c.getLastUid()).isEqualTo(3);
    }

    @Test
    void privateRequest_linkToPrivateRequests() {
        PriceRequest p = pr("SENT", 0, Source.PRIVATE_REQUEST);
        writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId())).text("Получили, ответим завтра").build(), 5);
        assertThat(rows().get(0).getNotifyText()).contains("частная заявка ZZW-")
                .endsWith("/private-requests?openId=" + p.getTender().getId() + "&market=KZ");
    }

    @Test
    void htmlOnly_excerptIsText() {
        writer().write(mail().html("<style>p{color:red}</style><p>Цена 7 000 тг</p>").build(), 5);
        assertThat(rows().get(0).getExcerpt()).isEqualTo("Цена 7 000 тг");
    }

    /**
     * Ответ поставщика только в HTML: цену и отказ ищут в сыром HTML, как прежде, — у разбора своё снятие тегов.
     * В готовом тексте {@code <30 дней … Анна <anna@zzw.kz>} — обычные символы, но разбор принял бы их за один тег и
     * выбросил вместе с ценой. Заметка запроса — текст для людей, без тегов.
     */
    @Test
    void htmlOnlySupplierReply_priceFromRawHtml_noteIsText() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .html("<p>Срок поставки &lt;30 дней</p><p>Цена 1 500 000 тг</p><p>Менеджер: Анна &lt;anna@zzw.kz&gt;</p>")
                .build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.PRICE_PARSED);
        PriceRequest back = prRepo.findById(p.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("RESPONDED");
        assertThat(back.getItems().get(0).getResponsePrice()).isEqualByComparingTo("1500000");
        assertThat(back.getNote()).isEqualTo("Срок поставки <30 дней\nЦена 1 500 000 тг\nМенеджер: Анна <anna@zzw.kz>");
    }

    /** То же для отказа: формула отказа между {@code <} и {@code >} готового текста пропала бы вместе с ними. */
    @Test
    void htmlOnlySupplierRefusal_declineFromRawHtml() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .html("<p>Срок &lt;30 дней нам не подходит.</p><p>Данную позицию мы не поставляем.</p>"
                        + "<p>Анна &lt;anna@zzw.kz&gt;</p>")
                .build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.DECLINED);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("DECLINED");
    }

    @Test
    void manualPrice_notOverwritten_priceSet() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        p.getItems().get(0).setResponsePrice(new BigDecimal("2500000"));
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Цена 3 200 000 тенге").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.PRICE_SET);
        PriceRequest back = prRepo.findById(p.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("RESPONDED");
        assertThat(back.getItems().get(0).getResponsePrice()).isEqualByComparingTo("2500000");
        assertThat(rows().get(0).getNotifyText()).contains("Цена в АИС уже введена: 2\u00A0500\u00A0000,00 ₸");
    }
}
