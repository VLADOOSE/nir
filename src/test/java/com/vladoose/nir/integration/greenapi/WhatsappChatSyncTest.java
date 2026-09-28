package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class WhatsappChatSyncTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatLeadRules rules;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;

    FakeGreenApiClient fake;
    FakeWestmedClient westmed;
    WhatsappStatusHolder status;
    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGreenApiClient();
        westmed = new FakeWestmedClient();
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    /** Предел файла — 1 МБ, чтобы тест «больше предела» не гонял 25 МБ. */
    private WhatsappChatSync sync(ChatIngestWriter w) {
        return new WhatsappChatSync(fake, w, westmed, status, "https://westmed.kz/", 1, 5);
    }
    private WhatsappChatSync sync() { return sync(writer); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String group() { return "120363" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L) + "@g.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private Chat chat(String externalChatId) {
        return chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, GreenApiJson.ACCOUNT, externalChatId).orElseThrow();
    }

    private ChatMessage lastMessage(String externalChatId) {
        return messageRepository.findLatest(chat(externalChatId).getId(), PageRequest.of(0, 1)).get(0);
    }

    @Test
    void drainWritesEachNotificationThenDeletesIt() {
        String a = personal();
        String b = personal();
        long r1 = fake.enqueue(GreenApiJson.incoming(a, "Айгерим", id(), clock += 60, GreenApiJson.text("Здравствуйте")));
        long r2 = fake.enqueue(GreenApiJson.incoming(b, "Ерлан", id(), clock += 60, GreenApiJson.text("Нужен УЗИ")));

        assertThat(sync().drain(10)).isTrue();

        assertThat(fake.deleted).containsExactly(r1, r2);
        assertThat(lastMessage(a).getBody()).isEqualTo("Здравствуйте");
        assertThat(lastMessage(b).getBody()).isEqualTo("Нужен УЗИ");
        assertThat(status.snapshot(true, true).getLastMessageAt()).isNotNull();
    }

    @Test
    void failedWriteLeavesNotificationInQueueAndThirdFailureDropsIt() {
        ChatIngestWriter broken = new ChatIngestWriter(chatRepository, messageRepository, attachmentRepository, rules, intake, leadService) {
            @Override
            public Outcome write(ParsedNotification.Message m, IncomingFile file, List<IncomingLead.Item> cart) {
                throw new IllegalStateException("сбой записи");
            }
        };
        long r = fake.enqueue(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));
        WhatsappChatSync s = sync(broken);

        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isFalse();
        assertThat(fake.deleted).isEmpty();                       // не записано — ждёт в очереди
        assertThat(status.lastError()).contains("сбой записи");

        assertThat(s.drain(10)).isTrue();                          // третья неудача — «ядовитое», удаляем
        assertThat(fake.deleted).containsExactly(r);
        assertThat(status.snapshot(true, true).getWarnings()).contains(WhatsappStatusHolder.MESSAGE_DROPPED);
    }

    @Test
    void stateQuotaAndUnknownNotificationsAreAcknowledged() {
        fake.enqueue(GreenApiJson.state("blocked"));
        fake.enqueue(GreenApiJson.quota());
        fake.enqueue(GreenApiJson.webhook("outgoingMessageStatus"));

        assertThat(sync().drain(10)).isTrue();

        assertThat(fake.deleted).hasSize(3);
        WhatsappStatusResponse s = status.snapshot(true, true);
        assertThat(s.getState()).isEqualTo("blocked");
        assertThat(s.getWarnings()).contains(WhatsappStatusHolder.QUOTA_EXCEEDED);
    }

    @Test
    void cartTemplateGetsBrandsFromSiteCatalog() {
        westmed.productsBySearch.put("облучатель обн-150", List.of(new WestmedProduct("Облучатель ОБН-150", "obn-150", "Азов")));
        String chat = personal();
        fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text(
                "Здравствуйте! Интересует следующее оборудование:\n\n1. Облучатель ОБН-150 (x2)\n\nПрошу подготовить коммерческое предложение.")));

        sync().drain(10);

        Lead l = leadRepository.findByChatIdIn(List.of(chat(chat).getId())).get(0);
        assertThat(l.getSubject()).isEqualTo("Запрос КП");
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getBrand, LeadItem::getQuantity, LeadItem::getProductUrl)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn-150"));
    }

    @Test
    void filesAreDownloadedForPersonalChatsOnly() {
        String url = "https://files.example/photo.jpg";
        fake.files.put(url, new byte[]{1, 2, 3});
        String chat = personal();
        String grp = group();
        fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.file("imageMessage", url, "photo.jpg", "image/jpeg", "")));
        fake.enqueue(GreenApiJson.group(grp, "Коллеги", "77025556677@c.us", "Данияр", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://files.example/group.jpg", "group.jpg", "image/jpeg", "")));

        sync().drain(10);

        assertThat(fake.downloads).containsExactly(url);
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(chat).getId(), lastMessage(grp).getId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("photo.jpg", null), tuple("group.jpg", AttachmentNotStoredReason.GROUP));
    }

    @Test
    void fileOverLimitIsNotStored() {
        String url = "https://files.example/big.pdf";
        fake.files.put(url, new byte[1024 * 1024 + 1]);
        String chat = personal();
        fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.file("documentMessage", url, "big.pdf", "application/pdf", "ТЗ")));

        sync().drain(10);

        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(chat).getId())))
                .singleElement().extracting(ChatAttachmentMeta::notStoredReason).isEqualTo(AttachmentNotStoredReason.TOO_LARGE);
    }

    @Test
    void failingDownloadIsRetriedThenMessageKeptWithoutFile() {
        String url = "https://files.example/flaky.pdf";
        fake.files.put(url, new byte[]{1});
        fake.downloadFailuresLeft.put(url, 5);
        String chat = personal();
        long r = fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("documentMessage", url, "ТЗ.pdf", "application/pdf", "Вот ТЗ")));
        WhatsappChatSync s = sync();

        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isTrue();

        assertThat(fake.deleted).containsExactly(r);
        ChatMessage m = lastMessage(chat);
        assertThat(m.getBody()).isEqualTo("Вот ТЗ");
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(m.getId())))
                .singleElement().extracting(ChatAttachmentMeta::notStoredReason).isEqualTo(AttachmentNotStoredReason.DOWNLOAD_FAILED);
    }

    @Test
    void rejectedKeyIsNotCountedAsBrokenNotification() {
        fake.enqueue(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));
        fake.failReceiveWith = new GreenApiAuthException(401, "Green-API отклонил ключ (HTTP 401) при приёме сообщений");

        assertThatThrownBy(() -> sync().drain(10)).isInstanceOf(GreenApiAuthException.class);
        assertThat(fake.deleted).isEmpty();
    }
}
