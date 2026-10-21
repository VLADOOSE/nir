package com.vladoose.nir.tgbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.telegram.*;
import com.vladoose.nir.service.tgbot.TelegramBotPoller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Приём нажатий (спека 2026-10-21-tender-telegram-bot §8): накопившееся при старте — мимо, сбой одного — не стоп, 409 — стоп. */
class TelegramBotPollerTest {

    TelegramStubServer stub;
    final List<BotUpdate> handled = new ArrayList<>();
    final List<Long> sleeps = new ArrayList<>();

    @BeforeEach void up() throws Exception { stub = TelegramStubServer.start(0); }
    @AfterEach void down() { stub.close(); }

    TelegramBotPoller poller() {
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        return new TelegramBotPoller(api, u -> {
            if ("boom".equals(u.callback() == null ? null : u.callback().data())) throw new IllegalStateException("x");
            handled.add(u);
        }, true, ms -> { sleeps.add(ms); throw new InterruptedException(); });
    }

    static String cb(long id, String data) {
        return "{\"update_id\":" + id + ",\"callback_query\":{\"id\":\"c" + id + "\",\"from\":{\"id\":1},"
                + "\"message\":{\"message_id\":5,\"chat\":{\"id\":-1001}},\"data\":\"" + data + "\"}}";
    }

    @Test
    void skipPending_movesOffsetPastLast() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[" + cb(41, "d:1:ai") + "]"), TelegramStubServer.Reply.json("[]"));
        assertThat(poller().skipPending()).isEqualTo(42);
        assertThat(stub.requests().get(1).body()).contains("\"offset\":42");
        assertThat(handled).isEmpty();   // нажатие до старта не выполняется
    }

    @Test
    void pollOnce_failureOfOneDoesNotStopOthers() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[" + cb(5, "boom") + "," + cb(6, "d:1:ai") + "]"));
        assertThat(poller().pollOnce(5)).isEqualTo(7);
        assertThat(handled).extracting(BotUpdate::updateId).containsExactly(6L);
    }

    @Test
    void conflict409_stopsWithoutSleep_401_sleeps10min() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[]"), TelegramStubServer.Reply.error(409, "Conflict"));
        TelegramBotPoller p = poller();
        p.loop();
        assertThat(sleeps).isEmpty();
        stub.on("getUpdates", TelegramStubServer.Reply.json("[]"), TelegramStubServer.Reply.error(401, "Unauthorized"));
        poller().loop();
        assertThat(sleeps).containsExactly(600_000L);
    }

    @Test
    void tooMany_sleepsRetryAfter() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[]"), TelegramStubServer.Reply.tooMany(7));
        poller().loop();
        assertThat(sleeps).containsExactly(7_000L);
    }
}
