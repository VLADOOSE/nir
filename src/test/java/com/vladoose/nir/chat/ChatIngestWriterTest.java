package com.vladoose.nir.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.greenapi.IncomingFile;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** Правила чат ↔ обращение (спека whatsapp-chats §5.2) на реальной базе, без сети. */
@SpringBootTest
@Transactional
class ChatIngestWriterTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired EntityManager em;

    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String group() { return "120363" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L) + "@g.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private static ParsedNotification.Message parse(ObjectNode body) {
        return (ParsedNotification.Message) GreenApiNotificationParser.parse(body);
    }
    private ParsedNotification.Message in(String chat, String text) {
        return parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text(text)));
    }
    private ParsedNotification.Message out(String chat, String text) {
        return parse(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text(text)));
    }
    private ChatIngestWriter.Outcome write(ParsedNotification.Message m) { return writer.write(m, null, List.of()); }
    private List<Lead> leadsOf(ChatIngestWriter.Outcome o) { return leadRepository.findByChatIdIn(List.of(o.chatId())); }

    @Test
    void firstMessageOfPersonalChatCreatesChatAndNewLead() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(in(chat, "Нужен облучатель ОБН-150"));

        Chat c = chatRepository.findById(o.chatId()).orElseThrow();
        assertThat(c.getMarket()).isEqualTo(Market.KZ);
        assertThat(c.getTitle()).isEqualTo("Айгерим");
        assertThat(c.getPhoneNorm()).isEqualTo("+" + chat.substring(0, 11));
        assertThat(c.getLastMessagePreview()).isEqualTo("Нужен облучатель ОБН-150");
        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getChannel()).isEqualTo(LeadChannel.WHATSAPP);
        assertThat(l.getSource()).isEqualTo(LeadSources.WHATSAPP);
        assertThat(l.getSubject()).isEqualTo("WhatsApp");
        assertThat(l.getContactName()).isEqualTo("Айгерим");
        assertThat(l.getMessage()).isEqualTo("Нужен облучатель ОБН-150");
        assertThat(l.getChat().getId()).isEqualTo(c.getId());
        assertThat(l.getEvents()).extracting(LeadEvent::getBody).containsExactly("Сообщение в WhatsApp");
    }

    @Test
    void nextMessagesGoToTheSameLead() {
        String chat = personal();
        write(in(chat, "Здравствуйте"));
        ChatIngestWriter.Outcome second = write(in(chat, "Нужен облучатель"));

        assertThat(second.createdLeadId()).isNull();
        assertThat(leadsOf(second)).hasSize(1);
        assertThat(messageRepository.findLatest(second.chatId(), PageRequest.of(0, 10))).hasSize(2);
    }

    @Test
    void phoneReplyTakesNewLeadIntoWork() {
        String chat = personal();
        ChatIngestWriter.Outcome first = write(in(chat, "Здравствуйте"));
        write(out(chat, "Добрый день! Подготовим КП"));

        Lead l = leadRepository.findById(first.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.STATUS);
            assertThat(e.getAuthor()).isNull();
            assertThat(e.getBody()).contains("ответ клиенту в WhatsApp с телефона");
        });
    }

    @Test
    void siteLeadWithSamePhoneIsContinuedAndItsSiteStatusQueued() {
        String chat = personal();
        Lead site = intake.ingest(new IncomingLead(LeadSources.WESTMED, "price:zz-" + id(), LeadChannel.SITE,
                "Заявка с сайта", OffsetDateTime.now().minusDays(1), "Айгерим", "+" + chat.substring(0, 11), null, null,
                "Нужен аппарат", List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();

        ChatIngestWriter.Outcome o = write(in(chat, "Это я оставляла заявку на сайте"));
        write(out(chat, "Видим заявку, работаем"));

        assertThat(o.createdLeadId()).isNull();
        Lead l = leadRepository.findById(site.getId()).orElseThrow();
        assertThat(l.getChat().getId()).isEqualTo(o.chatId());
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getExtStatusPending()).isEqualTo("PROCESSED");
    }

    @Test
    void outgoingFirstCreatesNoLead() {
        ChatIngestWriter.Outcome o = write(out(personal(), "Добрый день, это West-Med"));

        assertThat(o.createdLeadId()).isNull();
        assertThat(leadsOf(o)).isEmpty();
        assertThat(messageRepository.findLatest(o.chatId(), PageRequest.of(0, 5))).hasSize(1);
    }

    @Test
    void messageAfterClosedLeadStartsNewLead() {
        String chat = personal();
        ChatIngestWriter.Outcome first = write(in(chat, "Нужен облучатель"));
        leadService.close(first.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");

        ChatIngestWriter.Outcome again = write(in(chat, "А теперь нужен рециркулятор"));

        assertThat(again.createdLeadId()).isNotNull().isNotEqualTo(first.createdLeadId());
    }

    @Test
    void convertedLeadIsContinuedWithin30DaysButNotAfter() {
        String recent = personal();
        ChatIngestWriter.Outcome r = write(in(recent, "Нужен облучатель"));
        age(r, 10);
        assertThat(write(in(recent, "Когда будет КП?")).createdLeadId()).isNull();

        String stale = personal();
        ChatIngestWriter.Outcome s = write(in(stale, "Нужен облучатель"));
        age(s, 40);
        assertThat(write(in(stale, "Теперь нужен рециркулятор")).createdLeadId()).isNotNull();
    }

    /** Обращение чата — «Заявка создана», вся активность N дней назад (через SQL: @PreUpdate переписал бы updated_at). */
    private void age(ChatIngestWriter.Outcome o, int days) {
        em.flush();
        em.createNativeQuery("update lead set status = 'CONVERTED', updated_at = now() - (:d * interval '1 day') where chat_id = :c")
                .setParameter("d", days).setParameter("c", o.chatId()).executeUpdate();
        em.createNativeQuery("update chat set last_message_at = now() - (:d * interval '1 day') where id = :c")
                .setParameter("d", days).setParameter("c", o.chatId()).executeUpdate();
        em.clear();
    }

    @Test
    void notClientChatNeverStartsLeads() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(out(chat, "Привет, это Данияр"));
        chatRepository.findById(o.chatId()).orElseThrow().setNotClient(true);

        assertThat(write(in(chat, "Ок, до завтра")).createdLeadId()).isNull();
        assertThat(leadsOf(o)).isEmpty();
    }

    @Test
    void groupMessagesAreStoredWithoutLeads() {
        ChatIngestWriter.Outcome o = write(parse(GreenApiJson.group(group(), "Коллеги West-Med", "77025556677@c.us",
                "Данияр", id(), clock += 60, GreenApiJson.text("Кто едет в Уральск?"))));

        Chat c = chatRepository.findById(o.chatId()).orElseThrow();
        assertThat(c.isGroup()).isTrue();
        assertThat(c.getTitle()).isEqualTo("Коллеги West-Med");
        assertThat(leadsOf(o)).isEmpty();
        assertThat(messageRepository.findLatest(o.chatId(), PageRequest.of(0, 1)).get(0).getSenderName()).isEqualTo("Данияр");
    }

    @Test
    void redeliveredMessageIsNotDuplicated() {
        ParsedNotification.Message m = in(personal(), "Здравствуйте");
        ChatIngestWriter.Outcome first = write(m);
        ChatIngestWriter.Outcome again = write(m);

        assertThat(again.duplicate()).isTrue();
        assertThat(messageRepository.findLatest(first.chatId(), PageRequest.of(0, 5))).hasSize(1);
        assertThat(leadsOf(first)).hasSize(1);
    }

    @Test
    void siteCartTemplateBecomesQuoteRequestWithItems() {
        List<IncomingLead.Item> cart = List.of(
                new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn-150"),
                new IncomingLead.Item("Рециркулятор СН-111-130", null, 1, null));

        ChatIngestWriter.Outcome o = writer.write(in(personal(),
                "Здравствуйте! Интересует следующее оборудование:\n\n1. Облучатель ОБН-150 (x2)\n2. Рециркулятор СН-111-130\n\nПрошу подготовить коммерческое предложение."),
                null, cart);

        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getSubject()).isEqualTo("Запрос КП");
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getBrand, LeadItem::getQuantity)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2), tuple("Рециркулятор СН-111-130", null, 1));
        assertThat(l.getEvents()).extracting(LeadEvent::getBody).containsExactly("Запрос КП, 2 поз. — WhatsApp");
    }

    @Test
    void editChangesOriginalAndDeleteKeepsText() {
        String chat = personal();
        ParsedNotification.Message original = in(chat, "Нужен один облучатель");
        ChatIngestWriter.Outcome o = write(original);
        write(parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.edited(original.idMessage(), "Нужны два облучателя"))));

        ChatMessage edited = messageRepository.findByChatIdAndExternalId(o.chatId(), original.idMessage()).orElseThrow();
        assertThat(edited.getBody()).isEqualTo("Нужны два облучателя");
        assertThat(edited.isEdited()).isTrue();
        assertThat(messageRepository.findLatest(o.chatId(), PageRequest.of(0, 5))).hasSize(1);

        writer.applyDelete(new ParsedNotification.Delete(GreenApiJson.ACCOUNT, chat, original.idMessage()));

        ChatMessage deleted = messageRepository.findByChatIdAndExternalId(o.chatId(), original.idMessage()).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getBody()).isEqualTo("Нужны два облучателя");
    }

    /** Автоответ бота/интеграции на том же инстансе не должен уводить каждое новое обращение из «Новых». */
    @Test
    void messageSentViaApiDoesNotTakeLeadIntoWork() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(in(chat, "Нужен облучатель"));

        write(parse(GreenApiJson.outgoingApi(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.text("Спасибо за обращение! Ответим в течение часа."))));

        assertThat(leadRepository.findById(o.createdLeadId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(messageRepository.findLatest(o.chatId(), PageRequest.of(0, 5))).hasSize(2);   // в переписке оно есть
    }

    /** Правка без текста (например, подписи к файлу — форма уведомления не проверена) не стирает сообщение. */
    @Test
    void editWithoutTextKeepsPreviousBody() {
        String chat = personal();
        ParsedNotification.Message original = in(chat, "Нужен облучатель");
        ChatIngestWriter.Outcome o = write(original);

        write(parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.edited(original.idMessage(), ""))));

        ChatMessage m = messageRepository.findByChatIdAndExternalId(o.chatId(), original.idMessage()).orElseThrow();
        assertThat(m.getBody()).isEqualTo("Нужен облучатель");
        assertThat(m.isEdited()).isTrue();
    }

    @Test
    void editOfLastMessageUpdatesChatPreviewButEditOfOlderDoesNot() {
        String chat = personal();
        ParsedNotification.Message first = in(chat, "Нужен один облучатель");
        ChatIngestWriter.Outcome o = write(first);
        ParsedNotification.Message last = in(chat, "И рециркулятор");
        write(last);

        // превью — текст ПОСЛЕДНЕГО сообщения (спека §6.6): иначе список показывает текст, которого в переписке уже нет
        write(parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.edited(last.idMessage(), "И два рециркулятора"))));
        assertThat(chatRepository.findById(o.chatId()).orElseThrow().getLastMessagePreview()).isEqualTo("И два рециркулятора");

        write(parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.edited(first.idMessage(), "Нужны два облучателя"))));
        assertThat(chatRepository.findById(o.chatId()).orElseThrow().getLastMessagePreview()).isEqualTo("И два рециркулятора");
    }

    @Test
    void fileIsStoredOrMarkedNotStored() {
        String chat = personal();
        ParsedNotification.Message photo = parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://x/p", "photo.jpg", "image/jpeg", "")));
        ParsedNotification.Message video = parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("videoMessage", "https://x/v", "big.mp4", "video/mp4", "")));

        ChatIngestWriter.Outcome p = writer.write(photo, IncomingFile.stored(photo.file(), new byte[]{9, 8, 7}), List.of());
        ChatIngestWriter.Outcome v = writer.write(video, IncomingFile.notStored(video.file(), AttachmentNotStoredReason.TOO_LARGE), List.of());

        assertThat(attachmentRepository.findMetaByMessageIds(List.of(p.messageId(), v.messageId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::sizeBytes, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("photo.jpg", 3L, null), tuple("big.mp4", null, AttachmentNotStoredReason.TOO_LARGE));
        assertThat(chatRepository.findById(p.chatId()).orElseThrow().getLastMessagePreview()).isEqualTo("[видео]");
        assertThat(leadRepository.findById(p.createdLeadId()).orElseThrow().getMessage()).isEqualTo("[фото]");
    }

    @Test
    void hiddenNumberChatIsContinuedByChatNotPhone() {
        String hidden = ThreadLocalRandom.current().nextLong(100_000_000_000_000L, 999_999_999_999_999L) + "@lid";
        ChatIngestWriter.Outcome first = write(in(hidden, "Добрый день"));
        ChatIngestWriter.Outcome second = write(in(hidden, "Нужен аппарат"));

        assertThat(leadRepository.findById(first.createdLeadId()).orElseThrow().getContactPhone()).isNull();
        assertThat(second.createdLeadId()).isNull();
    }
}
