package com.vladoose.nir.chat;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.mail.EmailLetter;
import com.vladoose.nir.service.mail.MailFile;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class EmailChatWriterTest {

    static final String BOX = "info@westmed.kz";

    @Autowired EmailChatWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired EntityManager em;

    OffsetDateTime clock = OffsetDateTime.now().minusHours(2);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    private EmailLetter letter(String from, String name, String subject, String body, MailFile... files) {
        clock = clock.plusMinutes(5);
        return new EmailLetter("<" + UUID.randomUUID() + "@x.kz>", subject, body, clock, from, name, List.of(files));
    }

    private List<Lead> leadsOf(Long chatId) { return leadRepository.findByChatIdIn(List.of(chatId)); }

    /** Обращение — «Заявка создана», вся активность N дней назад (через SQL: @PreUpdate переписал бы updated_at). */
    private void age(Long chatId, int days) {
        em.flush();
        em.createNativeQuery("update lead set status = 'CONVERTED', updated_at = now() - (:d * interval '1 day') where chat_id = :c")
                .setParameter("d", days).setParameter("c", chatId).executeUpdate();
        em.createNativeQuery("update chat set last_message_at = now() - (:d * interval '1 day') where id = :c")
                .setParameter("d", days).setParameter("c", chatId).executeUpdate();
        em.clear();
    }

    @Test
    void firstLetterCreatesEmailChatMessageAndNewLead() {
        String from = address();
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(from, "Айгуль Сапарова", "Запрос цены", "Нужны кушетки 3 шт."));

        Chat c = chatRepository.findById(o.chatId()).orElseThrow();
        assertThat(c.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(c.getAccount()).isEqualTo(BOX);
        assertThat(c.getExternalChatId()).isEqualTo(from);
        assertThat(c.getTitle()).isEqualTo("Айгуль Сапарова");
        assertThat(c.getPhoneNorm()).isNull();
        assertThat(c.getMarket()).isEqualTo(Market.KZ);
        assertThat(c.getLastMessagePreview()).isEqualTo("Запрос цены — Нужны кушетки 3 шт.");
        ChatMessage m = messageRepository.findById(o.messageId()).orElseThrow();
        assertThat(m.getType()).isEqualTo(ChatMessageType.EMAIL);
        assertThat(m.getDirection()).isEqualTo(LeadDirection.IN);
        assertThat(m.getSubject()).isEqualTo("Запрос цены");
        assertThat(m.getBody()).isEqualTo("Нужны кушетки 3 шт.");
        assertThat(m.getSenderName()).isEqualTo("Айгуль Сапарова");
        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(l.getSource()).isEqualTo(LeadSources.EMAIL);
        assertThat(l.getSubject()).isEqualTo("Запрос цены");
        assertThat(l.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(l.getContactEmail()).isEqualTo(from);
        assertThat(l.getMessage()).isEqualTo("Нужны кушетки 3 шт.");
        assertThat(l.getReceivedAt().toInstant()).isEqualTo(m.getSentAt().toInstant());
        assertThat(l.getChat().getId()).isEqualTo(c.getId());
        assertThat(l.getEvents()).extracting(LeadEvent::getBody).containsExactly("Запрос цены — почта");
    }

    @Test
    void noSubjectBecomesLetter_noNameKeepsAddressAsTitle() {
        String from = address();
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(from, null, "", "Здравствуйте"));

        assertThat(chatRepository.findById(o.chatId()).orElseThrow().getTitle()).isEqualTo(from);
        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getSubject()).isEqualTo("Письмо");
        assertThat(l.getContactName()).isNull();
    }

    @Test
    void nextLettersGoToTheSameChatAndLead() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Нужны кушетки"));
        EmailChatWriter.Outcome second = writer.writeIncoming(BOX, letter(from, "Айгуль", "Re: Запрос", "И ширмы"));

        assertThat(second.chatId()).isEqualTo(first.chatId());
        assertThat(second.createdLeadId()).isNull();
        assertThat(leadsOf(first.chatId())).hasSize(1);
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().getLastMessagePreview()).isEqualTo("Re: Запрос — И ширмы");
    }

    @Test
    void ourReplyTakesLeadIntoWork() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Нужны кушетки"));
        EmailChatWriter.Outcome reply = writer.writeOutgoing(BOX, from, letter(BOX, null, "Re: Запрос", "Подготовим КП"));

        assertThat(reply.chatId()).isEqualTo(first.chatId());
        ChatMessage out = messageRepository.findById(reply.messageId()).orElseThrow();
        assertThat(out.getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(out.getSenderName()).isNull();
        Lead l = leadRepository.findById(first.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.STATUS);
            assertThat(e.getAuthor()).isNull();
            assertThat(e.getBody()).contains("ответ клиенту письмом из почты");
        });
    }

    /** Первый запуск читает «Отправленные» после «Входящих»: наше письмо, написанное ДО письма клиента, в работу не берёт. */
    @Test
    void ourLetterWrittenBeforeTheLeadDoesNotTakeIt() {
        String from = address();
        EmailLetter early = letter(BOX, null, "КП", "Направляем КП");
        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(from, "Айгуль", "Re: КП", "А есть дешевле?"));
        writer.writeOutgoing(BOX, from, early);

        assertThat(leadRepository.findById(in.createdLeadId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void weWroteFirst_chatWithoutLead_thenClientReplyStartsLead() {
        String to = address();
        EmailChatWriter.Outcome out = writer.writeOutgoing(BOX, to, letter(BOX, null, "Предложение", "Добрый день"));

        assertThat(out.createdLeadId()).isNull();
        assertThat(leadsOf(out.chatId())).isEmpty();
        assertThat(chatRepository.findById(out.chatId()).orElseThrow().getTitle()).isEqualTo(to);

        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(to, "Ерлан", "Re: Предложение", "Интересно"));
        assertThat(in.createdLeadId()).isNotNull();
        assertThat(chatRepository.findById(out.chatId()).orElseThrow().getTitle()).isEqualTo("Ерлан");
    }

    @Test
    void siteLeadWithSameEmailIsContinued_caseInsensitive_andSiteStatusQueued() {
        String from = address();
        Lead site = intake.ingest(new IncomingLead(LeadSources.WESTMED, "price:zz-" + UUID.randomUUID(), LeadChannel.SITE,
                "Заявка с сайта", OffsetDateTime.now().minusDays(1), "Айгуль", null, from.toUpperCase(Locale.ROOT), null,
                "Нужен аппарат", List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();

        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(from, "Айгуль", "Заявка", "Это я с сайта"));
        writer.writeOutgoing(BOX, from, letter(BOX, null, "Re: Заявка", "Видим, работаем"));

        assertThat(in.createdLeadId()).isNull();
        Lead l = leadRepository.findById(site.getId()).orElseThrow();
        assertThat(l.getChat().getId()).isEqualTo(in.chatId());
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getExtStatusPending()).isEqualTo("PROCESSED");
    }

    @Test
    void letterAfterClosedLeadStartsNewLead() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Нужны кушетки"));
        leadService.close(first.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");

        EmailChatWriter.Outcome again = writer.writeIncoming(BOX, letter(from, "Айгуль", "Новый запрос", "Теперь ширмы"));

        assertThat(again.createdLeadId()).isNotNull().isNotEqualTo(first.createdLeadId());
    }

    @Test
    void convertedLeadIsContinuedWithin30DaysButNotAfter() {
        String recent = address();
        EmailChatWriter.Outcome r = writer.writeIncoming(BOX, letter(recent, "Айгуль", "Запрос", "Нужны кушетки"));
        age(r.chatId(), 10);
        assertThat(writer.writeIncoming(BOX, letter(recent, "Айгуль", "Re: Запрос", "Когда КП?")).createdLeadId()).isNull();

        String stale = address();
        EmailChatWriter.Outcome s = writer.writeIncoming(BOX, letter(stale, "Ерлан", "Запрос", "Нужны кушетки"));
        age(s.chatId(), 40);
        assertThat(writer.writeIncoming(BOX, letter(stale, "Ерлан", "Новый запрос", "Теперь ширмы")).createdLeadId()).isNotNull();
    }

    @Test
    void notClientChat_storesLettersWithoutLeads() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeOutgoing(BOX, from, letter(BOX, null, "Привет", "Это мы"));
        chatRepository.findById(first.chatId()).orElseThrow().setNotClient(true);

        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(from, "Поставщик", "Каталог", "Наш каталог"));

        assertThat(in.createdLeadId()).isNull();
        assertThat(leadsOf(in.chatId())).isEmpty();
        assertThat(messageRepository.findLatest(in.chatId(), PageRequest.of(0, 5))).hasSize(2);
    }

    @Test
    void sameLetterTwice_isDuplicate() {
        String from = address();
        EmailLetter l = letter(from, "Айгуль", "Запрос", "Текст");
        writer.writeIncoming(BOX, l);

        EmailChatWriter.Outcome again = writer.writeIncoming(BOX, l);

        assertThat(again.duplicate()).isTrue();
        assertThat(messageRepository.findLatest(again.chatId(), PageRequest.of(0, 5))).hasSize(1);
        assertThat(leadsOf(again.chatId())).hasSize(1);
    }

    @Test
    void filesAreStored_bigOneMarkedTooLarge() {
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(address(), "Айгуль", "Смета", "Во вложении",
                new MailFile("смета.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 3L, new byte[]{1, 2, 3}),
                new MailFile("видео.mp4", "video/mp4", null, null)));

        assertThat(attachmentRepository.findMetaByMessageIds(List.of(o.messageId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::sizeBytes, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("смета.xlsx", 3L, null), tuple("видео.mp4", null, AttachmentNotStoredReason.TOO_LARGE));
    }

    @Test
    void facilityIsMatchedByEmail() {
        String from = address();
        Facility f = facilityRepository.save(Facility.builder().name("ZZ Клиника " + UUID.randomUUID()).email(from)
                .market(Market.KZ).build());

        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Текст"));

        assertThat(leadRepository.findById(o.createdLeadId()).orElseThrow().getFacility().getId()).isEqualTo(f.getId());
    }

    @Test
    void leadTextIsCutTo4000_letterKeptWhole() {
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(address(), null, "Длинное", "а".repeat(5000)));

        assertThat(leadRepository.findById(o.createdLeadId()).orElseThrow().getMessage()).hasSize(4000);
        assertThat(messageRepository.findById(o.messageId()).orElseThrow().getBody()).hasSize(5000);
    }
}
