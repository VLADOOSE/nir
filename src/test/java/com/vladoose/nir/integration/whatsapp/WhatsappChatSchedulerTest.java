package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.Chat;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.greenapi.FakeGreenApiClient;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiSettings;
import com.vladoose.nir.integration.greenapi.GreenApiSource;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** cycle() зовётся напрямую в потоке теста: так он входит в транзакцию теста и откатывается. */
@SpringBootTest
@Transactional
class WhatsappChatSchedulerTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;

    FakeGreenApiClient fake;
    GreenApiSource source;
    WhatsappStatusHolder status;

    @BeforeEach
    void setUp() {
        fake = new FakeGreenApiClient();
        source = new GreenApiSource(fake, 5, 300_000, 3_600_000);
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    private WhatsappChatSync sync() {
        return new WhatsappChatSync(source, writer, new FakeWestmedClient(), status, "https://westmed.kz", 25);
    }

    private WhatsappChatScheduler scheduler(boolean enabled) { return scheduler(enabled, 300_000); }

    private WhatsappChatScheduler scheduler(boolean enabled, long stallMs) {
        return new WhatsappChatScheduler(sync(), source, status, enabled, "KZ", 600_000, 30_000, stallMs);
    }

    @Test
    void cycleWritesChatsOfConfiguredMarketEvenOnDefaultThread() {
        String chatId = "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
        fake.enqueue(GreenApiJson.incoming(chatId, "Айгерим", "ID-" + System.nanoTime(), Instant.now().getEpochSecond(),
                GreenApiJson.text("Здравствуйте")));
        MarketContext.clear();   // у фонового потока рынка нет — дефолт RF (§6 CLAUDE.md)

        scheduler(true).cycle();

        MarketContext.set(Market.RF);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, GreenApiJson.ACCOUNT, chatId)).isEmpty();
        MarketContext.set(Market.KZ);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, GreenApiJson.ACCOUNT, chatId))
                .get().extracting(Chat::getMarket).isEqualTo(Market.KZ);
    }

    @Test
    void statusShowsStateNumberAndSettingsWarnings() {
        fake.settings = new GreenApiSettings("77000000001@c.us", "https://hooks.example/wa", true, false);
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();

        WhatsappStatusResponse st = s.status();
        assertThat(st.isEnabled()).isTrue();
        assertThat(st.isConfigured()).isTrue();
        assertThat(st.getState()).isEqualTo("authorized");
        assertThat(st.getNumber()).isEqualTo("77000000001");
        assertThat(st.getWarnings()).containsExactly(WhatsappStatusHolder.WEBHOOK_URL_SET, WhatsappStatusHolder.OUTGOING_PHONE_OFF);
        assertThat(st.getLastError()).isNull();
    }

    @Test
    void rejectedKeyPausesFurtherCycles() {
        fake.failReceiveWith = new GatewayAuthException(401,
                "Green-API отклонил ключ (HTTP 401) при приёме сообщений — проверьте idInstance и токен");
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();
        assertThat(s.status().getLastError()).contains("отклонил ключ").contains("повтор через 10 мин");
        int calls = fake.receiveCalls;

        fake.failReceiveWith = null;
        s.cycle();
        assertThat(fake.receiveCalls).isEqualTo(calls);   // пауза: в Green-API не ходили
    }

    @Test
    void missingCredentialsAreReportedWithoutCalls() {
        fake.configured = false;
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();

        assertThat(s.status().getLastError()).contains("не заданы учётные данные Green-API");
        assertThat(fake.receiveCalls).isZero();
    }

    @Test
    void disabledSchedulerStillReportsStatus() {
        assertThat(scheduler(false).status().isEnabled()).isFalse();
    }

    /** Строку состояния видит любой вошедший: чужой текст исключения (бывает с адресом и токеном) туда не попадает. */
    @Test
    void foreignExceptionTextNeverReachesStatusLine() {
        fake.failReceiveWith = new IllegalStateException("https://api.example/waInstance1101/receiveNotification/tok-secret");
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();

        assertThat(s.status().getLastError()).contains("IllegalStateException")
                .doesNotContain("tok-secret").doesNotContain("waInstance");
    }

    /** Error (нехватка памяти на большом файле) не должен крутить голову очереди раз в секунду молча. */
    @Test
    void errorInsideCycleIsReportedAndPaused() {
        fake.failReceiveWithError = new CycleTestError();
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();
        assertThat(s.status().getLastError()).contains("CycleTestError");
        int calls = fake.receiveCalls;

        fake.failReceiveWithError = null;
        s.cycle();
        assertThat(fake.receiveCalls).isEqualTo(calls);   // пауза
    }

    static class CycleTestError extends Error {}

    /** Опечатка в WHATSAPP_MARKET молча превращалась в RF — чаты уходили на рынок, где их никто не ищет. */
    @Test
    void unknownMarketStopsIntakeInsteadOfWritingToRf() {
        WhatsappChatScheduler s = new WhatsappChatScheduler(sync(), source, status, true, "KZZ", 600_000, 30_000, 300_000);

        s.cycle();

        assertThat(s.status().getLastError()).contains("WHATSAPP_MARKET").contains("KZZ");
        assertThat(fake.receiveCalls).isZero();
    }

    @Test
    void statusTellsWhichMarketTheMirrorWritesTo() {
        assertThat(scheduler(true).status().getMarket()).isEqualTo("KZ");
    }

    @Test
    void statusTellsWhichGatewayIsActive() {
        assertThat(scheduler(true).status().getProvider()).isEqualTo("greenapi");
    }

    /** Ревью 2026-09-28: застрявший проход держал «подключён» без ошибки — со стороны неотличимо от тишины в чатах. */
    @Test
    void stalledCycleIsVisibleInStatus() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.onReceive = () -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        WhatsappChatScheduler s = scheduler(true, 100);
        try {
            s.tick();
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);

            assertThat(s.status().getLastError()).contains("не продвигается");
        } finally {
            release.countDown();
            s.shutdown();
        }
    }
}
