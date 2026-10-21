package com.vladoose.nir.tgbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramStubServer;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.aianswer.AiAnswerService;
import com.vladoose.nir.service.productwatch.ProductAiBrief;
import com.vladoose.nir.service.tgbot.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нажатия и ответы боту (спека 2026-10-21-tender-telegram-bot §8): кто нажал, чужой чат, «📋 Для ИИ» файлом, «✍️ Ответ ИИ»
 * и разбор блока, подсказка «блок не найден». Бренд «ZZQXW» — ни один бренд поставщиков nirdb в него не входит.
 */
@SpringBootTest
@Transactional
class BotUpdateRouterTest {
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired ProductGroupRepository groups;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired DistributorRepository distributors;
    @Autowired UserAccountRepository users;
    @Autowired ProductDigestRepository digests;
    @Autowired BotLetterRepository letters;
    @Autowired com.vladoose.nir.service.PriceRequestWriter writer;
    @Autowired ProductAiBrief brief;
    @Autowired AiAnswerService aiAnswers;
    @Autowired BotRunAs runAs;
    @Autowired TenderLotRepository lots;
    @Autowired com.vladoose.nir.service.PriceRequestSendService sendService;
    @Autowired com.vladoose.nir.service.RfqDocumentService documents;
    @Autowired com.vladoose.nir.service.DistributorService distributorService;
    @Autowired com.vladoose.nir.service.TenderService tenderService;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired com.vladoose.nir.service.CompanyProfileService profiles;
    TelegramStubServer stub;
    BotUpdateRouter router;
    BotPrompts prompts;
    TenderLot lot;
    SiteProduct dki;
    ProductDigest digest;

    @BeforeEach
    void setUp() throws Exception {
        stub = TelegramStubServer.start(0);
        stub.on("getMe", TelegramStubServer.Reply.json("{\"id\":777,\"is_bot\":true}"));
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        prompts = new BotPrompts(digests);
        BotLetterStore store = new BotLetterStore(letters, lots);
        router = new BotUpdateRouter(api, users, runAs, new BotBriefSender(api, digests, brief, prompts),
                new BotAnswerHandler(aiAnswers, store, api, prompts), new BotLetterActions(api, store, sendService, documents,
                distributorService, tenderService, lots, priceRequests, profiles, writer, null));
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", "ZZQXW");
        TgFixtures.match(matches, lot, TgFixtures.group(groups).getId(), ProductMatchLevel.TYPE, null, List.of(dki.getId()));
        TgFixtures.distributor(distributors, "ZZ Аксион завод", "zz-axion@example.kz", true, false, "ZZQXW");
        TgFixtures.user(users, "zzbot", "ROLE_ADMIN", 111L);
        TgFixtures.user(users, "zzop", "ROLE_USER", 222L);
        digest = digests.saveAndFlush(ProductDigest.builder().messageId(500L).tenderIds(new ArrayList<>(List.of(lot.getTender().getId()))).build());
    }

    @AfterEach void down() { stub.close(); MarketContext.clear(); }

    BotUpdate press(long from, String data) { return new BotUpdate(1, new BotUpdate.Callback("c1", from, -1001, 500, data), null); }
    BotUpdate reply(long from, long to, String text) { return new BotUpdate(2, null, new BotUpdate.Incoming(600, -1001, from, text, to, 777L)); }
    List<String> paths() { return stub.requests().stream().map(r -> r.path().substring(r.path().lastIndexOf('/') + 1)).toList(); }

    @Test
    void unknownUser_onlyAlertWithId() {
        router.handle(press(999, "d:" + digest.getId() + ":ai"));
        assertThat(paths()).containsExactly("answerCallbackQuery");
        assertThat(stub.requests().get(0).body()).contains("999").contains("\"show_alert\":true");
    }

    @Test
    void operatorRole_noAccess_otherChat_ignored() {
        router.handle(press(222, "d:" + digest.getId() + ":ai"));
        assertThat(stub.requests().get(0).body()).contains("Нет доступа");
        router.handle(new BotUpdate(3, new BotUpdate.Callback("c2", 111, -2002, 500, "d:1:ai"), null));
        assertThat(stub.requests()).hasSize(1);
    }

    @Test
    void briefButton_sendsFileWithCodes() {
        router.handle(press(111, "d:" + digest.getId() + ":ai"));
        MarketContext.set(Market.KZ);
        TelegramStubServer.Request doc = stub.requests().stream().filter(r -> r.path().endsWith("/sendDocument")).findFirst().orElseThrow();
        assertThat(doc.body()).contains("filename=\"Для ИИ — 1 тендер").contains("[L" + lot.getId() + "]").contains("Для АИС");
    }

    @Test
    void answerReply_pinsModel_summaryWithLetterButton_draftSaved() {
        router.handle(press(111, "d:" + digest.getId() + ":ans"));
        router.handle(reply(111, 500, "L" + lot.getId() + " | идти | P" + dki.getId() + " | ТЗ совпадает"));
        MarketContext.set(Market.KZ);
        TelegramStubServer.Request summary = stub.requests().get(stub.requests().size() - 1);
        assertThat(summary.path()).endsWith("/sendMessage");
        assertThat(summary.body()).contains("закреплено").contains("Письма к отправке").contains(":v\"");
        assertThat(lots.findById(lot.getId()).orElseThrow().isModelByAi()).isTrue();
        assertThat(letters.findAll()).filteredOn(b -> b.getTenderId().equals(lot.getTender().getId()))
                .singleElement().satisfies(b -> assertThat(b.getListMessageId()).isNotNull());
    }

    @Test
    void replyToHumanMessage_ignored() {
        router.handle(new BotUpdate(4, null, new BotUpdate.Incoming(601, -1001, 111,
                "L" + lot.getId() + " | идти | P" + dki.getId() + " | x", 500L, 555L)));   // ответ человеку, не боту
        assertThat(paths()).doesNotContain("sendMessage");
        assertThat(lots.findById(lot.getId()).orElseThrow().getProposedEquipment()).isNull();
    }

    @Test
    void replyWithoutBlock_onlyToPrompt_says_notFound() {
        router.handle(reply(111, 500, "спасибо"));            // ответ на сообщение-пачку — подсказка
        assertThat(stub.requests().get(stub.requests().size() - 1).body()).contains("не найден");
        int before = stub.requests().size();
        router.handle(reply(111, 4242, "спасибо"));           // ответ на чужое сообщение бота — молча
        assertThat(stub.requests()).hasSize(before);
    }

    /** «📱 Отправлю сам» → «✅ Отправил» — по кнопкам письма (l:<id>:m, l:<id>:h). */
    @Test
    void manualAndHandedButtons_route() {
        router.handle(press(111, "d:" + digest.getId() + ":ans"));
        router.handle(reply(111, 500, "L" + lot.getId() + " | идти | P" + dki.getId() + " | ТЗ совпадает"));
        MarketContext.set(Market.KZ);
        BotLetter b = letters.findAll().stream().filter(x -> x.getTenderId().equals(lot.getTender().getId())).findFirst().orElseThrow();
        router.handle(press(111, "l:" + b.getId() + ":m"));
        MarketContext.set(Market.KZ);
        assertThat(letters.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BotLetterStatus.MANUAL);
        router.handle(press(111, "l:" + b.getId() + ":h"));
        MarketContext.set(Market.KZ);
        assertThat(letters.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BotLetterStatus.HANDED);
    }

}
