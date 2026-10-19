package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientLetterRecorder;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.PrivateRequestService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Запись отправленного письма клиенту (спека 2026-10-19-client-mail-reply §3.3). */
@SpringBootTest
@Transactional
class ClientLetterRecorderTest {

    static final String BOX = "info@westmed.kz";

    @Autowired ClientLetterRecorder recorder;
    @Autowired EmailChatWriter chatWriter;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired DealEventRepository events;
    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferService offers;
    @Autowired EntityManager em;

    final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    private Chat emailChat(String address) {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX).externalChatId(address)
                .title("Клиент").build());
    }

    private Lead lead(Chat chat, LeadChannel channel, String email) {
        return leads.save(Lead.builder().market(Market.KZ).channel(channel).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос").contactName("Клиент").contactEmail(email).status(LeadStatus.NEW).chat(chat)
                .receivedAt(now.minusHours(1)).build());
    }

    private EmailLetter letter() {
        return new EmailLetter("<ais." + UUID.randomUUID() + "@westmed.kz>", "Re: Запрос цены",
                "Здравствуйте!\n\nС уважением,\nИван Петров", now, BOX, null,
                List.of(new MailFile("КП.pdf", "application/pdf", 2L, new byte[]{1, 2})));
    }

    private ClientLetterRecorder.Sent sent(List<String> to, EmailLetter l, Long leadId, Long tenderId, List<Long> offerIds) {
        return new ClientLetterRecorder.Sent(BOX, to, l, "Иван Петров", "zz-sender", leadId, tenderId, offerIds);
    }

    @Test
    void reply_outgoingMessageWithKeyNameFiles_leadTakenInWorkWithAuthor() {
        String a = address();
        Chat chat = emailChat(a);
        Lead lead = lead(chat, LeadChannel.EMAIL, a);
        EmailLetter l = letter();

        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a), l, null, null, List.of()));

        assertThat(r.chatIds()).containsExactly(chat.getId());
        ChatMessage m = messages.findByChatIdAndExternalId(chat.getId(), l.key()).orElseThrow();
        assertThat(m.getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(m.getSenderName()).isEqualTo("Иван Петров");
        assertThat(m.getSubject()).isEqualTo("Re: Запрос цены");
        assertThat(attachments.findMetaByMessageIds(List.of(m.getId()))).hasSize(1);
        em.flush();
        em.refresh(lead);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(lead.getEvents()).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.STATUS);
            assertThat(e.getAuthor()).isEqualTo("zz-sender");
            assertThat(e.getBody()).endsWith(ClientLetterRecorder.TAKE_NOTE);
        });
    }

    @Test
    void mixedCaseAddress_sameChat() {   // Review Focus 2
        String a = address();
        Chat chat = emailChat(a);
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a.toUpperCase()), letter(), null, null, List.of()));
        assertThat(r.chatIds()).containsExactly(chat.getId());
        assertThat(chats.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, a.toUpperCase())).isEmpty();
    }

    @Test
    void ownDomainAndServiceAddresses_noChat() {
        ClientLetterRecorder.Result r = recorder.record(sent(List.of("manager@westmed.kz", "noreply@2gis.ru"), letter(), null, null, List.of()));
        assertThat(r.chatIds()).isEmpty();
        assertThat(r.messageIds()).isEmpty();
    }

    @Test
    void sameLetterFromSentFolder_isDuplicate() {
        String a = address();
        Chat chat = emailChat(a);
        EmailLetter l = letter();
        recorder.record(sent(List.of(a), l, null, null, List.of()));
        assertThat(chatWriter.writeOutgoing(BOX, a, l).duplicate()).isTrue();   // так пишет приём «Отправленных»
        assertThat(messages.findLatest(chat.getId(), PageRequest.of(0, 5))).hasSize(1);
    }

    @Test
    void dealContext_emailEventPerChat_atLetterTime() {
        Tender t = requests.createEmpty();
        String a = address();
        String b = address();
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a, b), letter(), null, t.getId(), List.of()));
        List<DealEvent> list = events.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(t.getId(), DealEventType.EMAIL);
        assertThat(list).extracting(DealEvent::getRefId).containsExactlyElementsOf(r.chatIds());
        assertThat(list).allSatisfy(e -> {
            assertThat(e.getOccurredAt()).isEqualTo(now);
            assertThat(e.getText()).isEqualTo("Письмо клиенту «Re: Запрос цены»");
            assertThat(e.getAuthor()).isEqualTo("zz-sender");
        });
    }

    @Test
    void offers_draftBecomesSentAtLetterTime_otherStatusesUnchanged() {
        ClientOffer draft = offers.create("zz-sender");
        ClientOffer accepted = offers.create("zz-sender");
        offers.setStatus(accepted.getId(), ClientOfferStatus.ACCEPTED);
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(address()), letter(), null, null,
                List.of(draft.getId(), accepted.getId())));
        assertThat(r.offersSent()).containsExactly(draft.getId());
        em.flush();
        em.refresh(draft);
        em.refresh(accepted);
        assertThat(draft.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(draft.getSentAt()).isEqualTo(now);
        assertThat(accepted.getStatus()).isEqualTo(ClientOfferStatus.ACCEPTED);
    }

    @Test
    void leadCard_whatsappLead_letterToEmail_messageEventOnLead() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("Клиент").build());
        String a = address();
        Lead lead = lead(wa, LeadChannel.WHATSAPP, a);
        recorder.record(sent(List.of(a), letter(), lead.getId(), null, List.of()));
        em.flush();
        em.refresh(lead);
        assertThat(lead.getChat().getId()).isEqualTo(wa.getId());
        assertThat(lead.getEvents()).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.MESSAGE);
            assertThat(e.getDirection()).isEqualTo(LeadDirection.OUT);
            assertThat(e.getChannel()).isEqualTo(LeadChannel.EMAIL);
            assertThat(e.getBody()).isEqualTo("Письмо клиенту «Re: Запрос цены» на " + a);
        });
    }

    @Test
    void leadWithoutChat_linkedToNewChat_noMessageEvent() {
        String a = address();
        Lead lead = lead(null, LeadChannel.SITE, a);
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a), letter(), lead.getId(), null, List.of()));
        em.flush();
        em.refresh(lead);
        assertThat(lead.getChat().getId()).isEqualTo(r.chatIds().get(0));
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(lead.getEvents()).noneMatch(e -> e.getType() == LeadEventType.MESSAGE);
    }
}
