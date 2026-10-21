package com.vladoose.nir.tgbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramStubServer;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.*;
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.tgbot.BotLetterActions;
import com.vladoose.nir.service.tgbot.BotLetterStore;
import com.vladoose.nir.service.tgbot.BotRunAs;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Письма из бота (спека 2026-10-21-tender-telegram-bot §7): фото бланка, «✅ Отправить» с проверками (двойное нажатие,
 * модель сменилась, отправлено из АИС, приём закрыт), сбой почты → тот же запрос. Шаблон письма рынка правит оператор —
 * в откатываемой транзакции он убран (ждём зашитый).
 */
@SmtpIntegrationTest
class BotLetterActionsTest {
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired SiteProductRepository products;
    @Autowired DistributorRepository distributors;
    @Autowired UserAccountRepository users;
    @Autowired BotLetterRepository letterRepo;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestWriter writer;
    @Autowired LotModelService models;
    @Autowired MedEquipmentRepository equipment;
    @Autowired BotRunAs runAs;
    @Autowired PriceRequestSendService sendService;
    @Autowired RfqDocumentService documents;
    @Autowired DistributorService distributorService;
    @Autowired TenderService tenderService;
    @Autowired CompanyProfileService profiles;
    @Autowired EmailTemplateRepository templates;
    TelegramStubServer stub;
    BotLetterActions actions;
    BotLetterStore store;
    UserAccount admin;
    TenderLot lot;
    Distributor maker;
    BotLetter draft;

    @BeforeEach
    void setUp() throws Exception {
        stub = TelegramStubServer.start(0);
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        store = new BotLetterStore(letterRepo, lots);
        actions = new BotLetterActions(api, store, sendService, documents, distributorService, tenderService, lots, priceRequests,
                profiles, writer, "https://ais.westmed.kz");
        MarketContext.set(Market.KZ);
        templates.deleteAllInBatch();
        admin = TgFixtures.user(users, "zzbot", "ROLE_ADMIN", 111L);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        SiteProduct dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", "ZZАксион");
        maker = TgFixtures.distributor(distributors, "ZZ Аксион завод", "zz-axion@example.kz", true, false, "ZZАксион");
        models.applyByAi(lot, models.equipmentForSiteProduct(dki), "ИИ: подходит");
        draft = store.createDrafts(List.of(new AiAnswerResult.LetterPlan(lot.getTender().getId(), lot.getTender().getTenderNumber(),
                "ZZ Горбольница №7", maker.getId(), maker.getName(), true, List.of(lot.getId()), List.of("ZZ Дефибриллятор"))), "zzbot").get(0);
        store.attachList(List.of(draft.getId()), 700);
    }

    @AfterEach void down() { stub.close(); MarketContext.clear(); }

    BotUpdate.Callback cb(String data) { return new BotUpdate.Callback("c", 111, -1001, 700, data); }
    void as(Runnable r) { runAs.run(admin, r); MarketContext.set(Market.KZ); }
    BotLetter reload() { return letterRepo.findById(draft.getId()).orElseThrow(); }

    /** Текст письма и Word во вложении — как в PrivateRequestRfqStage2Test. */
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
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            try (XWPFDocument d = new XWPFDocument(p.getInputStream()); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
                return x.getText().replace('\u00A0', ' ');
            }
        }
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = document(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    @Test
    void view_sendsPhotoWithCaptionAndButtons() {
        as(() -> actions.view(cb("l:" + draft.getId() + ":v"), draft.getId()));
        TelegramStubServer.Request photo = stub.requests().stream().filter(r -> r.path().endsWith("/sendPhoto")).findFirst().orElseThrow();
        assertThat(photo.body()).contains("Письмо 1 из 1").contains("Кому: zz-axion@example.kz")
                .contains("l:" + draft.getId() + ":s").contains("l:" + draft.getId() + ":b").contains("kp=" + maker.getId());
        assertThat(reload().getPreviewMessageId()).isNotNull();
    }

    @Test
    void send_deliversLetterWithClientLineAndDocx_thenSecondPressDoesNothing() throws Exception {
        as(() -> actions.view(cb("v"), draft.getId()));
        as(() -> actions.send(cb("s"), draft.getId()));
        MimeMessage[] got = greenMail.getReceivedMessages();
        assertThat(got).hasSize(1);
        assertThat(got[0].getAllRecipients()[0].toString()).isEqualTo("zz-axion@example.kz");
        assertThat(body(got[0])).contains("Конечный заказчик: ZZ Горбольница №7")
                .doesNotContain(lot.getTender().getTenderNumber());   // тендер не раскрываем
        assertThat(document(got[0])).contains("Конечный заказчик: ZZ Горбольница №7");   // Word во вложении — с той же строкой
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.SENT);
        assertThat(priceRequests.findById(b.getPriceRequestId()).orElseThrow().getCreatedBy()).startsWith("zzbot").endsWith(", Telegram");
        assertThat(stub.requests()).extracting(r -> r.path().substring(r.path().lastIndexOf('/') + 1))
                .contains("deleteMessage", "editMessageReplyMarkup");
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    void modelChanged_noLetter_backToDraftWithFreshItems() {
        MedEquipment other = equipment.saveAndFlush(MedEquipment.builder().name("ZZ Другая").manufact("ZZАксион").market(Market.KZ).build());
        models.apply(lot, other, null);
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.DRAFT);
        assertThat(b.getItems().get(0).equipmentId()).isEqualTo(other.getId());
        assertThat(stub.requests()).filteredOn(r -> r.path().endsWith("/sendMessage"))
                .anySatisfy(r -> assertThat(r.body()).contains("Модель лота изменилась"));
    }

    @Test
    void sentFromAisMeanwhile_elsewhere() {
        TenderLot l = lots.findById(lot.getId()).orElseThrow();
        writer.persist(l.getTender(), maker, List.of(new PriceRequestWriter.Line(l, l.getProposedEquipment(), 1)));
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.ELSEWHERE);
    }

    @Test
    void closedTender_cancelled() {
        Tender t = lot.getTender();
        t.setDeadlineAt(LocalDateTime.now(Market.KZ.zone()).minusHours(1));
        tenders.saveAndFlush(t);
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.CANCELLED);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void badAddress_failed_thenResendSameRequest() {
        maker.setEmail("не адрес");
        distributors.saveAndFlush(maker);
        as(() -> actions.send(cb("s"), draft.getId()));
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.FAILED);
        Long prId = b.getPriceRequestId();
        assertThat(prId).isNotNull();
        maker.setEmail("zz-axion@example.kz");
        distributors.saveAndFlush(maker);
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.SENT);
        assertThat(reload().getPriceRequestId()).isEqualTo(prId);   // тот же запрос, не второй
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    /** «📱 Отправлю сам»: запрос с номером, «ждём подтверждения» (CREATED, без времени отправки); PDF с этим номером и кнопками. */
    @Test
    void manual_createsPendingRequest_sendsPdfWithButtons_noEmail() throws Exception {
        as(() -> actions.view(cb("v"), draft.getId()));
        as(() -> actions.manual(cb("m"), draft.getId()));
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.MANUAL);
        PriceRequest pr = priceRequests.findById(b.getPriceRequestId()).orElseThrow();
        assertThat(pr.getStatus()).isEqualTo("CREATED");
        assertThat(pr.getSentAt()).isNull();
        assertThat(pr.getLetterStatus()).isEqualTo(LetterStatus.MANUAL);
        assertThat(pr.isDiscloseClient()).isTrue();
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        TelegramStubServer.Request doc = stub.requests().stream().filter(r -> r.path().endsWith("/sendDocument")).reduce((a, c) -> c).orElseThrow();
        assertThat(doc.body()).contains("Запрос КП № " + pr.getId()).contains("application/pdf")
                .contains("copy_text").contains("Конечный заказчик: ZZ Горбольница №7")
                .contains("l:" + draft.getId() + ":h").contains("l:" + draft.getId() + ":u");
        // второе нажатие — второго запроса нет
        as(() -> actions.manual(cb("m"), draft.getId()));
        assertThat(priceRequests.findAll().stream().filter(p -> p.getDistributor().getId().equals(maker.getId())).count()).isEqualTo(1);
    }

    @Test
    void handed_confirmsSent_listShowsManual() {
        as(() -> actions.manual(cb("m"), draft.getId()));
        as(() -> actions.handed(cb("h"), draft.getId()));
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.HANDED);
        assertThat(b.getSentBy()).startsWith("zzbot").endsWith(", Telegram");
        PriceRequest pr = priceRequests.findById(b.getPriceRequestId()).orElseThrow();
        assertThat(pr.getStatus()).isEqualTo("SENT");
        assertThat(pr.getSentAt()).isNotNull();
        assertThat(stub.requests()).filteredOn(r -> r.path().endsWith("/editMessageReplyMarkup"))
                .anySatisfy(r -> assertThat(r.body()).contains("✅ 1 · сам"));
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void undo_deletesPendingRequest_backToDraft() {
        as(() -> actions.manual(cb("m"), draft.getId()));
        Long prId = reload().getPriceRequestId();
        as(() -> actions.undo(cb("u"), draft.getId()));
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.DRAFT);
        assertThat(b.getPriceRequestId()).isNull();
        assertThat(priceRequests.findById(prId)).isEmpty();
        as(() -> actions.send(cb("s"), draft.getId()));   // дальше — письмом, как обычно
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    /** Подтверждение из зеркала WhatsApp: исходящий файл «Запрос КП № N …» с рабочего номера АИС. */
    @Test
    void whatsappFile_confirmsPendingRequest() {
        as(() -> actions.manual(cb("m"), draft.getId()));
        Long prId = reload().getPriceRequestId();
        actions.confirmFromWhatsapp(prId, "ZZ Аксион (WhatsApp)", java.time.OffsetDateTime.now());
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.HANDED);
        assertThat(priceRequests.findById(prId).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(stub.requests()).filteredOn(r -> r.path().endsWith("/sendMessage"))
                .anySatisfy(r -> assertThat(r.body()).contains("передан в WhatsApp").contains("ZZ Аксион (WhatsApp)"));
        java.time.OffsetDateTime sentAt = priceRequests.findById(prId).orElseThrow().getSentAt();
        // повтор того же файла — ничего не меняет (и время передачи — первое)
        actions.confirmFromWhatsapp(prId, "ZZ Аксион (WhatsApp)", java.time.OffsetDateTime.now().plusHours(2));
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.HANDED);
        assertThat(priceRequests.findById(prId).orElseThrow().getSentAt()).isEqualTo(sentAt);
    }

    /** Письмо не ушло (почта) — «📱 Отправлю сам» берёт тот же запрос, второго нет. */
    @Test
    void manualAfterFailedEmail_reusesRequest() {
        maker.setEmail("не адрес");
        distributors.saveAndFlush(maker);
        as(() -> actions.send(cb("s"), draft.getId()));
        Long prId = reload().getPriceRequestId();
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.FAILED);
        as(() -> actions.manual(cb("m"), draft.getId()));
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.MANUAL);
        assertThat(b.getPriceRequestId()).isEqualTo(prId);
        PriceRequest pr = priceRequests.findById(prId).orElseThrow();
        assertThat(pr.getLetterStatus()).isEqualTo(LetterStatus.MANUAL);
        assertThat(pr.getStatus()).isEqualTo("CREATED");
    }

    @Test
    void manual_closedTender_cancelled_noRequest() {
        Tender t = lot.getTender();
        t.setDeadlineAt(LocalDateTime.now(Market.KZ.zone()).minusHours(1));
        tenders.saveAndFlush(t);
        as(() -> actions.manual(cb("m"), draft.getId()));
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.CANCELLED);
        assertThat(reload().getPriceRequestId()).isNull();
    }

}
