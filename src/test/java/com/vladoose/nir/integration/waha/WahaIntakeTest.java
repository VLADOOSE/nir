package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.integration.whatsapp.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.ChatLeadRules;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** Вебхук WAHA → очередь → общий цикл → чаты и обращения (спека whatsapp-waha §5, §6, §8) на реальной базе. */
@SpringBootTest
@Transactional
class WahaIntakeTest {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatLeadRules rules;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired WhatsappInboxRepository repository;
    @Autowired WahaInboxWriter inbox;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    FakeWahaClient fake;
    WhatsappStatusHolder status;
    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE whatsapp_inbox SET status = 'DONE' WHERE status = 'PENDING'");
        MarketContext.set(Market.KZ);
        fake = new FakeWahaClient();
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    /** Предел файла — 1 МБ; задержка «отлёживания» — 0, события в прошлом. */
    private WhatsappChatSync sync(ChatIngestWriter w) {
        WahaInboxSource source = new WahaInboxSource(repository, inbox, fake, new WahaSessionManager(fake, "westmed", "KZ"),
                new WahaChatNames(fake), objectMapper, "test-hmac-key", 0, 60_000);
        return new WhatsappChatSync(source, w, new FakeWestmedClient(), status, "https://westmed.kz", 1);
    }

    private WhatsappChatSync sync() { return sync(writer); }

    private long queue(ObjectNode env) {
        String rid = "req-" + env.get("id").asText();
        inbox.insert(env, rid, env.toString());
        return repository.findByRequestId(rid).get(0).getId();
    }

    private WhatsappInboxEvent row(long id) { return repository.findById(id).orElseThrow(); }

    private Chat chat(String externalChatId) {
        return chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, WahaJson.ACCOUNT, externalChatId).orElseThrow();
    }

    private ChatMessage lastMessage(String externalChatId) {
        return messageRepository.findLatest(chat(externalChatId).getId(), PageRequest.of(0, 1)).get(0);
    }

    private ChatIngestWriter writerThrowing(RuntimeException e) {
        return new ChatIngestWriter(chatRepository, messageRepository, attachmentRepository, rules, intake, leadService) {
            @Override
            public Outcome write(ParsedNotification.Message m, IncomingFile file, List<IncomingLead.Item> cart) {
                throw e;
            }
        };
    }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }

    static String group() { return "120363" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L) + "@g.us"; }

    static String raw() { return "3EB0" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(); }

    @Test
    void queuedEventsBecomeChatsAndLeadsAndAreDone() {
        String c = personal();
        long in = queue(WahaJson.incomingText(c, "Айгерим", raw(), clock += 60, "Здравствуйте, нужен облучатель"));
        long out = queue(WahaJson.phoneReply(c, raw(), clock += 60, "Добрый день! Подготовим КП"));

        assertThat(sync().drain(10)).isTrue();

        assertThat(row(in).getStatus()).isEqualTo(WhatsappInboxStatus.DONE);
        assertThat(row(out).getStatus()).isEqualTo(WhatsappInboxStatus.DONE);
        assertThat(chat(c).getTitle()).isEqualTo("Айгерим");
        assertThat(lastMessage(c).getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(leadRepository.findByChatIdIn(List.of(chat(c).getId()))).singleElement()
                .extracting(Lead::getStatus).isEqualTo(LeadStatus.IN_WORK);
    }

    @Test
    void poisonEventIsDroppedAfterThreeAttemptsWithReason() {
        long id = queue(WahaJson.incomingText(personal(), "Айгерим", raw(), clock += 60, "x"));
        WhatsappChatSync s = sync(writerThrowing(new IllegalStateException("значение 'Айгерим' не влезло")));

        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isFalse();
        assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
        assertThat(s.drain(10)).isTrue();

        assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.DROPPED);
        assertThat(row(id).getLastError()).contains("IllegalStateException").doesNotContain("Айгерим");
        assertThat(status.snapshot(true, true).getWarnings()).contains(WhatsappStatusHolder.MESSAGE_DROPPED);
    }

    @Test
    void databaseOutageKeepsEventPending() {
        long id = queue(WahaJson.incomingText(personal(), "Айгерим", raw(), clock += 60, "x"));
        WhatsappChatSync s = sync(writerThrowing(new CannotCreateTransactionException("Could not open JPA EntityManager")));

        for (int i = 0; i < 6; i++) assertThat(s.drain(10)).isFalse();

        assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
        assertThat(status.lastError()).contains("база данных").contains("очереди АИС");
    }

    /** Предохранитель общего цикла на очереди WAHA: после 3 пропусков за сутки дальше не пропускаем — стоим с красной строкой. */
    @Test
    void fuseStopsDroppingAfterThreeADay() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) ids.add(queue(WahaJson.incomingText(personal(), "Айгерим", raw(), clock += 60, "x" + i)));
        WhatsappChatSync s = sync(writerThrowing(new IllegalStateException("регрессия записи")));

        for (int i = 0; i < 30; i++) s.drain(10);

        assertThat(ids.subList(0, 3)).allSatisfy(id -> assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.DROPPED));
        assertThat(row(ids.get(3)).getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
        assertThat(status.lastError()).contains("приём остановлен").contains("очереди АИС");
    }

    /** Размер из события больше предела — WAHA не трогаем вовсе: она держала бы весь файл в памяти (спека §6). */
    @Test
    void knownSizeOverLimitIsNotDownloaded() {
        String c = personal();
        queue(WahaJson.incomingFile(c, "Айгерим", raw(), clock += 60, "documentMessage", "Каталог.pdf", "application/pdf",
                40L * 1024 * 1024, "каталог"));

        sync().drain(10);

        assertThat(fake.calls).noneMatch(x -> x.startsWith("message ") || x.startsWith("download "));
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(c).getId())))
                .singleElement().extracting(ChatAttachmentMeta::notStoredReason).isEqualTo(AttachmentNotStoredReason.TOO_LARGE);
    }

    @Test
    void personalFileIsFetchedGroupFileIsNot() {
        String c = personal();
        String g = group();
        String rawP = raw();
        String id = WahaJson.messageId(false, c, rawP);
        String url = "http://ais-waha:3000/api/files/westmed/" + id + ".xlsx";
        fake.messages.put(id, WahaJson.M.createObjectNode().put("id", id).set("media", WahaJson.M.createObjectNode().put("url", url)));
        fake.files.put(url, new byte[]{1, 2, 3});
        queue(WahaJson.incomingFile(c, "Айгерим", rawP, clock += 60, "documentMessage", "Заявка.xlsx", XLSX, 3L, "список"));
        queue(WahaJson.groupFile(g, "77025556677@c.us", "Данияр", raw(), clock += 60, "imageMessage", null, "image/jpeg", 5_000L));

        sync().drain(10);

        assertThat(fake.calls).filteredOn(x -> x.startsWith("message ")).containsExactly("message " + c + " " + id + " true");
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(c).getId(), lastMessage(g).getId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("Заявка.xlsx", null), tuple(null, AttachmentNotStoredReason.GROUP));
    }

    @Test
    void incomingCallStartsCallLeadAndOutcomeCompletesTheLine() {
        String c = personal();
        String callId = "CALL" + raw();
        queue(WahaJson.call("call.received", callId, c, clock += 60, false, null));
        queue(WahaJson.call("call.accepted", callId, c, clock += 5, false, null));

        assertThat(sync().drain(10)).isTrue();

        assertThat(messageRepository.findLatest(chat(c).getId(), PageRequest.of(0, 5))).singleElement().satisfies(m -> {
            assertThat(m.getType()).isEqualTo(ChatMessageType.CALL);
            assertThat(m.getBody()).isEqualTo("📞 Входящий звонок — принят");
            assertThat(m.isEdited()).isFalse();
        });
        assertThat(leadRepository.findByChatIdIn(List.of(chat(c).getId()))).singleElement()
                .extracting(Lead::getSubject).isEqualTo("Звонок в WhatsApp");
    }

    @Test
    void editAndRevokeFromWaha() {
        String c = personal();
        String original = raw();
        queue(WahaJson.incomingText(c, "Айгерим", original, clock += 60, "Нужен УЗИ"));
        queue(WahaJson.edited(c, false, raw(), original, clock += 60, "Нужен УЗИ экспертного класса"));
        queue(WahaJson.revoked(c, false, raw(), original));        // время события — «сейчас», позже правки

        sync().drain(10);

        ChatMessage m = lastMessage(c);
        assertThat(m.getBody()).isEqualTo("Нужен УЗИ экспертного класса");
        assertThat(m.isEdited()).isTrue();
        assertThat(m.isDeleted()).isTrue();
    }
}
