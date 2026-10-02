package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.WhatsappInboxEvent;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.integration.whatsapp.*;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class WahaInboxSourceTest {

    @Autowired WhatsappInboxRepository repository;
    @Autowired WahaInboxWriter inbox;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatMessageRepository messages;

    FakeWahaClient fake;
    WahaSessionManager sessions;
    final long now = Instant.now().getEpochSecond();

    /** Чужие PENDING из dev-базы не должны мешать порядку: в транзакции теста (она откатится) считаем их разобранными. */
    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE whatsapp_inbox SET status = 'DONE' WHERE status = 'PENDING'");
        fake = new FakeWahaClient();
        sessions = new WahaSessionManager(fake, "westmed", "KZ");
    }

    WahaInboxSource source(long settleMs) { return source(settleMs, new CountingCatchUp()); }

    WahaInboxSource source(long settleMs, WahaCatchUp catchUp) {
        return new WahaInboxSource(repository, inbox, fake, sessions, new WahaChatNames(fake), catchUp, objectMapper,
                "test-hmac-key", settleMs, 60_000, 600_000, 7, 30);
    }

    /** Догонка-счётчик: сама история проверена в WahaCatchUpTest, здесь — когда её зовут. */
    class CountingCatchUp extends WahaCatchUp {
        int runs;
        RuntimeException failWith;

        CountingCatchUp() { super(fake, inbox, messages, objectMapper, 10); }

        @Override
        public int run(String session, String account) {
            runs++;
            if (failWith != null) throw failWith;
            return 0;
        }
    }

    long queue(ObjectNode env) {
        String rid = "req-" + env.get("id").asText();
        inbox.insert(env, rid, env.toString());
        return repository.findByRequestId(rid).get(0).getId();
    }

    static WhatsappNotification note(ObjectNode env) { return new WhatsappNotification(1, env); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }

    static String raw() { return "3EB0" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(); }

    /** Порядок — по времени события; моложе задержки (3 с) — не берём: правка не должна обогнать оригинал. */
    @Test
    void nextIsOldestSettledPendingEvent() {
        long later = queue(WahaJson.incomingText(personal(), "Айгерим", raw(), now - 60, "второе"));
        long earlier = queue(WahaJson.incomingText(personal(), "Ерлан", raw(), now - 120, "первое"));
        queue(WahaJson.incomingText(personal(), "Сауле", raw(), now - 1, "ещё отлёживается"));
        WahaInboxSource s = source(3000);

        WhatsappNotification first = s.next();
        assertThat(first.id()).isEqualTo(earlier);
        assertThat(first.body().path("payload").path("body").asText()).isEqualTo("первое");
        s.ack(first, null);
        assertThat(s.next().id()).isEqualTo(later);
        s.ack(new WhatsappNotification(later, null), null);
        assertThat(s.next()).isNull();
    }

    /**
     * Метка времени из далёкого будущего (часы телефона, битое событие) не держит событие в PENDING вечно: в очередь
     * оно ложится не позже «приём + 1 мин» — его разберут, а уборка потом уберёт.
     */
    @Test
    void farFutureTimestampIsCappedSoEventIsNotStuck() {
        long id = queue(WahaJson.incomingText(personal(), "Айгерим", raw(), now + 86_400, "из будущего"));

        assertThat(repository.findById(id).orElseThrow().getEventAt().toEpochSecond()).isLessThanOrEqualTo(now + 65);
    }

    @Test
    void ackMarksDoneOrDroppedWithReason() {
        long ok = queue(WahaJson.incomingText(personal(), "А", raw(), now - 60, "x"));
        long bad = queue(WahaJson.incomingText(personal(), "Б", raw(), now - 60, "y"));
        WahaInboxSource s = source(0);

        s.ack(new WhatsappNotification(ok, null), null);
        s.ack(new WhatsappNotification(bad, null), "внутренняя ошибка (IllegalStateException) — подробности в логе сервера");

        WhatsappInboxEvent done = repository.findById(ok).orElseThrow();
        WhatsappInboxEvent dropped = repository.findById(bad).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(WhatsappInboxStatus.DONE);
        assertThat(done.getLastError()).isNull();
        assertThat(done.getProcessedAt()).isNotNull();
        assertThat(dropped.getStatus()).isEqualTo(WhatsappInboxStatus.DROPPED);
        assertThat(dropped.getLastError()).contains("IllegalStateException");
    }

    @Test
    void fileIsFetchedThroughMessageMediaUrl() {
        String chat = personal();
        String id = WahaJson.messageId(false, chat, "3EB0F00D");
        String url = "http://ais-waha:3000/api/files/westmed/" + id + ".pdf";
        fake.messages.put(id, WahaJson.M.createObjectNode().put("id", id).set("media", WahaJson.M.createObjectNode().put("url", url)));
        fake.files.put(url, new byte[]{7, 7});

        byte[] bytes = source(0).download(new FileRef(id, "ТЗ.pdf", "application/pdf", 2L), 1024);

        assertThat(bytes).containsExactly(7, 7);
        assertThat(fake.calls).containsExactly("message " + chat + " " + id + " true", "download " + url);
    }

    @Test
    void messageWithoutMediaUrlIsDownloadFailure() {
        String id = WahaJson.messageId(false, personal(), "3EB0F00E");
        fake.messages.put(id, WahaJson.M.createObjectNode().put("id", id).set("media", WahaJson.M.createObjectNode().putNull("url")));

        assertThatThrownBy(() -> source(0).download(new FileRef(id, "a.pdf", "application/pdf"), 1024))
                .isInstanceOf(GatewayException.class);
    }

    /** Имя чата — из записной книжки телефона (как у Green-API), тема группы — из справочника; с кешем. */
    @Test
    void namesComeFromWahaDirectoriesAndAreCached() {
        String chat = personal();
        fake.contacts.put(chat, new WahaContact(chat, "Айгерим (клиника «Шипагер»)", "Aigerim"));
        fake.groups.put("120363000000000001@g.us", "Коллеги West-Med");
        WahaInboxSource s = source(0);

        ParsedNotification.Message first = (ParsedNotification.Message) s.parse(note(WahaJson.incomingText(chat, "Aigerim", raw(), now, "x")));
        ParsedNotification.Message second = (ParsedNotification.Message) s.parse(note(WahaJson.phoneReply(chat, raw(), now, "y")));
        ParsedNotification.Message group = (ParsedNotification.Message) s.parse(
                note(WahaJson.groupText("120363000000000001@g.us", "77025556677@c.us", "Данияр", raw(), now, "z")));

        assertThat(first.chatName()).isEqualTo("Айгерим (клиника «Шипагер»)");
        assertThat(second.chatName()).isEqualTo("Айгерим (клиника «Шипагер»)");
        assertThat(fake.count("contact ")).isEqualTo(1);
        assertThat(group.chatName()).isEqualTo("Коллеги West-Med");
        assertThat(group.senderName()).isEqualTo("Данияр");
    }

    /** Справочник WAHA упал — сообщение не ждёт: имя профиля из события, телефон как есть. */
    @Test
    void directoryOutageDoesNotHoldMessages() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе контакта: ConnectException");
        String chat = personal();

        ParsedNotification.Message m = (ParsedNotification.Message) source(0).parse(note(WahaJson.incomingText(chat, "Aigerim", raw(), now, "x")));

        assertThat(m.chatName()).isEqualTo("Aigerim");
        assertThat(m.phone()).isEqualTo("+" + chat.substring(0, 11));
    }

    @Test
    void hiddenNumberGetsPhoneFromLidDirectory() {
        fake.lids.put("123456789012345@lid", "+77012223344");

        ParsedNotification.Message m = (ParsedNotification.Message) source(0).parse(
                note(WahaJson.incomingText("123456789012345@lid", "Скрытый", raw(), now, "x")));

        assertThat(m.phone()).isEqualTo("+77012223344");
    }

    /** Номер сессии неизвестен (в событии нет me, сессию ещё не спрашивали) — не гадаем: попытка не удалась. */
    @Test
    void unknownAccountIsGatewayFailure() {
        ObjectNode env = WahaJson.incomingText(personal(), "А", raw(), now, "x");
        env.remove("me");

        assertThatThrownBy(() -> source(0).parse(note(env))).isInstanceOf(GatewayException.class);
    }

    /** WAHA не отвечает — красная строка, но уже принятые события разбираются: housekeeping не бросает. */
    @Test
    void unreachableWahaIsRedLineNotStop() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе состояния сессии: ConnectException");
        WhatsappStatusHolder status = new WhatsappStatusHolder();

        source(0).housekeeping(status);

        assertThat(status.snapshot(true, true).getLastError()).contains("WAHA недоступен").contains("принятые сообщения разбираются");
    }

    @Test
    void workingSessionIsCaughtUpOnceUntilInterval() {
        fake.session = new WahaSession("westmed", "WORKING", WahaJson.ME, "West-Med");
        CountingCatchUp catchUp = new CountingCatchUp();
        WahaInboxSource s = source(0, catchUp);
        WhatsappStatusHolder status = new WhatsappStatusHolder();

        s.housekeeping(status);
        s.housekeeping(status);

        assertThat(catchUp.runs).isEqualTo(1);
    }

    /** Пока номер не привязан, истории нет — догонку не зовём. */
    @Test
    void notWorkingSessionIsNotCaughtUp() {
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);
        CountingCatchUp catchUp = new CountingCatchUp();

        source(0, catchUp).housekeeping(new WhatsappStatusHolder());

        assertThat(catchUp.runs).isZero();
    }

    /** Сессия снова WORKING (событие session.status) — догоняем сразу, не дожидаясь 10 мин. */
    @Test
    void workingEventTriggersCatchUpAgain() {
        fake.session = new WahaSession("westmed", "WORKING", WahaJson.ME, "West-Med");
        CountingCatchUp catchUp = new CountingCatchUp();
        WahaInboxSource s = source(0, catchUp);
        WhatsappStatusHolder status = new WhatsappStatusHolder();
        s.housekeeping(status);

        s.parse(note(WahaJson.sessionStatus("WORKING")));
        s.housekeeping(status);

        assertThat(catchUp.runs).isEqualTo(2);
    }

    /** Догонка упала — предупреждение в строке, приём идёт; удалась — предупреждение снято. */
    @Test
    void catchUpFailureIsWarningNotStop() {
        fake.session = new WahaSession("westmed", "WORKING", WahaJson.ME, "West-Med");
        CountingCatchUp catchUp = new CountingCatchUp();
        catchUp.failWith = new GatewayException(0, "WAHA не ответил за 90 с при догонке по истории");
        WahaInboxSource s = source(0, catchUp);
        WhatsappStatusHolder status = new WhatsappStatusHolder();

        assertThatCode(() -> s.housekeeping(status)).doesNotThrowAnyException();
        assertThat(status.snapshot(true, true).getWarnings()).contains(WhatsappStatusHolder.CATCH_UP_FAILED);

        catchUp.failWith = null;
        s.parse(note(WahaJson.sessionStatus("WORKING")));
        s.housekeeping(status);
        assertThat(status.snapshot(true, true).getWarnings()).doesNotContain(WhatsappStatusHolder.CATCH_UP_FAILED);
    }

    /** Уборка (спека §5.4): DONE старше 7 дней, DROPPED старше 30; PENDING — никогда. */
    @Test
    void cleanupRemovesOnlyOldProcessedEvents() {
        long doneOld = queue(WahaJson.sessionStatus("WORKING"));
        long doneNew = queue(WahaJson.sessionStatus("WORKING"));
        long droppedOld = queue(WahaJson.sessionStatus("WORKING"));
        long droppedNew = queue(WahaJson.sessionStatus("WORKING"));
        long pendingOld = queue(WahaJson.sessionStatus("WORKING"));
        String set = "UPDATE whatsapp_inbox SET status = ?, processed_at = now() - make_interval(days => ?) WHERE id = ?";
        jdbc.update(set, "DONE", 8, doneOld);
        jdbc.update(set, "DONE", 1, doneNew);
        jdbc.update(set, "DROPPED", 31, droppedOld);
        jdbc.update(set, "DROPPED", 8, droppedNew);
        jdbc.update("UPDATE whatsapp_inbox SET received_at = now() - interval '40 days' WHERE id = ?", pendingOld);

        source(0).housekeeping(new WhatsappStatusHolder());

        assertThat(repository.findAllById(List.of(doneOld, doneNew, droppedOld, droppedNew, pendingOld)))
                .extracting(WhatsappInboxEvent::getId).containsExactlyInAnyOrder(doneNew, droppedNew, pendingOld);
    }

    /**
     * Событие смены статуса — повод сразу перечитать сессию: иначе после привязки строка «подключён» до минуты
     * была без номера (поймано живьём).
     */
    @Test
    void statusEventRefreshesSessionAtOnce() {
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);
        WahaInboxSource s = source(0);
        WhatsappStatusHolder status = new WhatsappStatusHolder();
        s.housekeeping(status);
        fake.session = new WahaSession("westmed", "WORKING", WahaJson.ME, "West-Med");

        // само событие статус не пишет: статус и номер — одним чтением сессии, иначе строка на миг «подключён» без номера
        assertThat(s.parse(note(WahaJson.sessionStatus("WORKING")))).isInstanceOf(ParsedNotification.Skip.class);
        s.housekeeping(status);

        assertThat(status.snapshot(true, true).getState()).isEqualTo("WORKING");
        assertThat(status.snapshot(true, true).getNumber()).isEqualTo(WahaJson.ACCOUNT);
    }
}
