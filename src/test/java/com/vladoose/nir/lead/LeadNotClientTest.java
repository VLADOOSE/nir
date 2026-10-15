package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.dto.request.LeadCloseRequest;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.mail.EmailLetter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadNotClientTest {

    @Autowired LeadController controller;
    @Autowired EmailChatWriter writer;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired ChatIngestWriter chatWriter;

    OffsetDateTime clock = OffsetDateTime.now().minusHours(2);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@supplier.cn"; }

    private EmailChatWriter.Outcome letterFrom(String from, String subject) {
        clock = clock.plusMinutes(5);
        return writer.writeIncoming("info@westmed.kz", new EmailLetter("<" + UUID.randomUUID() + "@x.kz>", subject,
                "Текст", clock, from, "Поставщик", List.of()));
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void notClient_closesLead_marksChat_nextLettersStartNoLead() {
        String from = address();
        EmailChatWriter.Outcome first = letterFrom(from, "Предложение о сотрудничестве");

        LeadCardResponse card = controller.notClient(first.createdLeadId());

        assertThat(card.getStatus()).isEqualTo("CLOSED");
        assertThat(card.getCloseReason()).isEqualTo("NOT_CLIENT");
        assertThat(card.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STATUS");
            assertThat(e.getBody()).contains("не клиент").contains(from);
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().isNotClient()).isTrue();

        EmailChatWriter.Outcome next = letterFrom(from, "Повторное предложение");
        assertThat(next.createdLeadId()).isNull();
        assertThat(leadRepository.findByChatIdIn(List.of(first.chatId()))).hasSize(1);
    }

    /**
     * «Не клиент» по ошибке: «Вернуть в работу» снимает и отметку переписки — иначе следующие письма клиента молча не
     * заводили бы обращений, а о второй половине отмены (на «Чатах») никто бы не вспомнил.
     */
    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void reopenAfterNotClient_unmarksChat_nextLetterStartsLeadAgain() {
        String from = address();
        EmailChatWriter.Outcome first = letterFrom(from, "Запрос");
        controller.notClient(first.createdLeadId());

        LeadCardResponse card = controller.reopen(first.createdLeadId());

        assertThat(card.getStatus()).isEqualTo("IN_WORK");
        assertThat(card.getCloseReason()).isNull();
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().isNotClient()).isFalse();
        assertThat(card.getEvents()).last().satisfies(e -> assertThat(e.getBody()).contains("«не клиент» снята"));
        leadService.close(first.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");
        assertThat(letterFrom(from, "Новый запрос").createdLeadId()).isNotNull();
    }

    /** Закрыто по другой причине, а переписку отметили «не клиент» на «Чатах» — «Вернуть в работу» отметку не трогает. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void reopenAfterOtherReason_keepsManualNotClientMark() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        leadService.close(o.createdLeadId(), LeadCloseReason.SPAM, null, "admin");
        chatRepository.findById(o.chatId()).orElseThrow().setNotClient(true);

        controller.reopen(o.createdLeadId());

        assertThat(chatRepository.findById(o.chatId()).orElseThrow().isNotClient()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void leadWithoutEmailChat_400_nothingChanges() {
        Lead phone = intake.ingest(new IncomingLead(LeadSources.MANUAL, null, LeadChannel.PHONE, "Звонок",
                OffsetDateTime.now(), "Иван", "+77011234567", null, null, "Нужен аппарат", List.of(),
                LeadStatus.IN_WORK, null, "admin")).orElseThrow();

        assertThatThrownBy(() -> controller.notClient(phone.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("с перепиской");
        assertThat(leadRepository.findById(phone.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.IN_WORK);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closedLead_400_chatNotMarked() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        leadService.close(o.createdLeadId(), LeadCloseReason.SPAM, null, "admin");

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(BadRequestException.class);
        assertThat(chatRepository.findById(o.chatId()).orElseThrow().isNotClient()).isFalse();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operator_forbidden() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void otherMarket_404() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        MarketContext.set(Market.RF);

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closeWithNotClientReason_400() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        LeadCloseRequest req = new LeadCloseRequest();
        req.setReason(LeadCloseReason.NOT_CLIENT);

        assertThatThrownBy(() -> controller.close(o.createdLeadId(), req)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cardShowsChatChannel() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");

        assertThat(controller.get(o.createdLeadId()).getChatChannel()).isEqualTo("EMAIL");
    }

    // --- WhatsApp (2026-10-15): кнопки «Не клиент» в карточке у обращений из WhatsApp не было — оператор её не нашёл ---

    long waClock = Instant.now().getEpochSecond() - 3600;

    static String waChat() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }

    private ChatIngestWriter.Outcome waIn(String chat, String text) {
        ParsedNotification.Message m = (ParsedNotification.Message) GreenApiNotificationParser.parse(GreenApiJson.incoming(
                chat, "Айгерим", UUID.randomUUID().toString().replace("-", "").toUpperCase(), waClock += 60, GreenApiJson.text(text)));
        return chatWriter.write(m, null, List.of());
    }

    private ChatIngestWriter.Outcome waCall(String chat) {
        ParsedNotification.Message m = new ParsedNotification.Message(GreenApiJson.ACCOUNT, chat, ChatKind.PERSONAL,
                "+" + chat.substring(0, chat.indexOf('@')), null, null, LeadDirection.IN, "call:" + UUID.randomUUID(),
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(waClock += 60), ZoneOffset.UTC), ChatMessageType.CALL,
                "📞 Входящий звонок", null, null, false);
        return chatWriter.write(m, null, List.of());
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void whatsappLead_notClient_closesLead_marksChat_nextMessagesAndCallsStartNoLead() {
        String chat = waChat();
        ChatIngestWriter.Outcome first = waIn(chat, "Привет, как дела?");

        LeadCardResponse card = controller.notClient(first.createdLeadId());

        assertThat(card.getStatus()).isEqualTo("CLOSED");
        assertThat(card.getCloseReason()).isEqualTo("NOT_CLIENT");
        assertThat(card.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STATUS");
            assertThat(e.getBody()).contains("не клиент").contains("сообщения и звонки").contains("+" + chat.substring(0, 11));
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().isNotClient()).isTrue();

        assertThat(waIn(chat, "Ты где?").createdLeadId()).isNull();
        assertThat(waCall(chat).createdLeadId()).isNull();
        assertThat(leadRepository.findByChatIdIn(List.of(first.chatId()))).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void whatsappReopen_unmarksChat_nextMessageStartsLeadAgain() {
        String chat = waChat();
        ChatIngestWriter.Outcome first = waIn(chat, "Нужен облучатель");
        controller.notClient(first.createdLeadId());

        LeadCardResponse card = controller.reopen(first.createdLeadId());

        assertThat(chatRepository.findById(first.chatId()).orElseThrow().isNotClient()).isFalse();
        assertThat(card.getEvents()).last()
                .satisfies(e -> assertThat(e.getBody()).contains("«не клиент» снята").contains("с этого номера"));
        leadService.close(first.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");
        assertThat(waIn(chat, "Ещё вопрос").createdLeadId()).isNotNull();
    }
}
