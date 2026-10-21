package com.vladoose.nir.tgbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.entity.ProductDigest;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramStubServer;
import com.vladoose.nir.repository.ProductDigestRepository;
import com.vladoose.nir.service.productwatch.ProductDigestComposer;
import com.vladoose.nir.service.tgbot.ProductDigestPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Отправка сообщения-пачки (спека 2026-10-21-tender-telegram-bot §4): кнопки несут id пачки; не ушло — пачки нет. */
@SpringBootTest
@Transactional
class ProductDigestPublisherTest {
    @Autowired ProductDigestRepository digests;
    TelegramStubServer stub;

    @BeforeEach void up() throws Exception { stub = TelegramStubServer.start(0); }
    @AfterEach void down() { stub.close(); }

    ProductDigestPublisher publisher() {
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        return new ProductDigestPublisher(digests, api, "https://ais.westmed.kz");
    }

    @Test
    void savesDigestWithMessageId_buttonsCarryId() throws Exception {
        stub.on("sendMessage", TelegramStubServer.Reply.ok(812));
        boolean ok = publisher().publish(new ProductDigestComposer.Chunk("🔎 x", true, List.of(5L, 7L)));
        assertThat(ok).isTrue();
        ProductDigest d = digests.findAll().stream().filter(x -> Long.valueOf(812).equals(x.getMessageId())).findFirst().orElseThrow();
        assertThat(d.getTenderIds()).containsExactly(5L, 7L);
        String body = stub.requests().get(0).body();
        assertThat(body).contains("\"d:" + d.getId() + ":ai\"").contains("\"d:" + d.getId() + ":ans\"")
                .contains("tenders?ids=5,7&market=KZ");
    }

    @Test
    void failure_returnsFalse_digestRemoved() {
        stub.on("sendMessage", TelegramStubServer.Reply.error(500, "x"));
        long before = digests.count();
        assertThat(publisher().publish(new ProductDigestComposer.Chunk("🔎 x", false, List.of(5L)))).isFalse();
        assertThat(digests.count()).isEqualTo(before);
    }
}
