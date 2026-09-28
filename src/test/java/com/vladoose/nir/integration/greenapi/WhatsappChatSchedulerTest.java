package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.Chat;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.Market;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** cycle() зовётся напрямую в потоке теста: так он входит в транзакцию теста и откатывается. */
@SpringBootTest
@Transactional
class WhatsappChatSchedulerTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;

    FakeGreenApiClient fake;
    WhatsappStatusHolder status;

    @BeforeEach
    void setUp() {
        fake = new FakeGreenApiClient();
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    private WhatsappChatScheduler scheduler(boolean enabled) {
        WhatsappChatSync sync = new WhatsappChatSync(fake, writer, new FakeWestmedClient(), status, "https://westmed.kz", 25, 5);
        return new WhatsappChatScheduler(sync, fake, status, enabled, "KZ", 600_000, 30_000, 300_000, 3_600_000);
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
        fake.failReceiveWith = new GreenApiAuthException(401,
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
}
