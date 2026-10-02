package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** Догонка (спека whatsapp-waha §5.3) на реальной базе, WAHA — фейк. */
@SpringBootTest
@Transactional
class WahaCatchUpTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatMessageRepository messages;
    @Autowired WahaInboxWriter inbox;
    @Autowired WhatsappInboxRepository repository;
    @Autowired ObjectMapper objectMapper;

    final FakeWahaClient fake = new FakeWahaClient();
    /** Свой номер на тест: отметка догонки считается по сообщениям номера — данные dev-базы не мешают. */
    final String account = "7799" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private WahaCatchUp catchUp() { return new WahaCatchUp(fake, inbox, messages, objectMapper, 10); }

    /** Сообщение этого номера со временем sentAtSec — как будто уже принятое. */
    private void stored(long sentAtSec) {
        String chat = "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
        writer.write(new ParsedNotification.Message(account, chat, ChatKind.PERSONAL, "+" + chat.substring(0, 11),
                "Айгерим", "Айгерим", LeadDirection.IN, "HIST" + sentAtSec + chat.substring(4, 11),
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(sentAtSec), ZoneOffset.UTC), ChatMessageType.TEXT, "x",
                null, null, false), null, List.of());
    }

    private static ObjectNode historyMessage(String rawId, long ts) {
        return (ObjectNode) WahaJson.incomingText("77015550000@c.us", "Айгерим", rawId, ts, "из истории").get("payload");
    }

    /** Первая привязка: сообщений номера ещё нет — отсчёт с привязки, старую переписку не тянем (решение 9). */
    @Test
    void firstLinkWithoutMessagesDoesNothing() {
        assertThat(catchUp().run("westmed", account)).isZero();
        assertThat(fake.count("history ")).isZero();
    }

    @Test
    void startsTenMinutesBeforeLatestMessageAndPagesToTheEnd() {
        long t = Instant.now().getEpochSecond() - 3600;
        stored(t - 500);
        stored(t);
        for (int i = 0; i < 250; i++) fake.history.add(historyMessage("3EB0" + account + "N" + i, t + i));

        assertThat(catchUp().run("westmed", account)).isEqualTo(250);
        long from = t - 600;
        assertThat(fake.calls).filteredOn(c -> c.startsWith("history "))
                .containsExactly("history " + from + " 100 0", "history " + from + " 100 100", "history " + from + " 100 200");
    }

    /**
     * Фильтру WAHA по времени догонка не доверяет вслепую: если он не сработал, история старше окна (переписка до
     * привязки) в очередь не попадает — иначе первая же догонка завела бы обращения по старым перепискам (решение 9).
     */
    @Test
    void historyOlderThanWindowIsSkippedEvenIfWahaIgnoredTheFilter() {
        long t = Instant.now().getEpochSecond() - 3600;
        stored(t);
        fake.ignoreHistoryFilter = true;
        fake.history.add(historyMessage("3EB0" + account + "OLD", t - 86_400));
        fake.history.add(historyMessage("3EB0" + account + "NEW", t + 5));

        assertThat(catchUp().run("westmed", account)).isEqualTo(1);
        assertThat(repository.findByMessageKey("false_3EB0" + account + "OLD")).isEmpty();
        assertThat(repository.findByMessageKey("false_3EB0" + account + "NEW")).hasSize(1);
    }

    /** Каждая страница истории отмечается: долгая догонка — движение приёма, а не «застрявший» проход. */
    @Test
    void everyHistoryPageIsReported() {
        long t = Instant.now().getEpochSecond() - 3600;
        stored(t);
        for (int i = 0; i < 150; i++) fake.history.add(historyMessage("3EB0" + account + "P" + i, t + i));
        int[] pages = {0};

        catchUp().run("westmed", account, () -> pages[0]++);

        assertThat(pages[0]).isEqualTo(2);
    }

    /** Сообщение с будущим sentAt (записано до ограничения) не усыпляет догонку до этой даты: отметка — не позже «сейчас». */
    @Test
    void futureLatestMessageDoesNotStallCatchUp() {
        long now = Instant.now().getEpochSecond();
        stored(now + 86_400);

        catchUp().run("westmed", account);

        assertThat(fake.calls).filteredOn(c -> c.startsWith("history ")).first().satisfies(c ->
                assertThat(Long.parseLong(c.split(" ")[1])).isLessThanOrEqualTo(now));
    }

    @Test
    void messageAlreadyQueuedByWebhookIsNotDuplicated() {
        long t = Instant.now().getEpochSecond() - 3600;
        stored(t);
        String rawId = "3EB0" + account + "W";
        ObjectNode webhook = WahaJson.incomingText("77015550000@c.us", "Айгерим", rawId, t + 5, "из вебхука");
        inbox.insert(webhook, "req-" + rawId, webhook.toString());
        fake.history.add(webhook.get("payload").deepCopy());

        assertThat(catchUp().run("westmed", account)).isZero();
        assertThat(repository.findByMessageKey("false_" + rawId)).hasSize(1);
    }
}
