package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTimelineService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Письма сделки в ленте «Переписка и история» (спека 2026-10-19-client-mail-reply §7.1). */
@SpringBootTest
@Transactional
class DealTimelineEmailTest {

    @Autowired DealTimelineService timeline;
    @Autowired PrivateRequestService requests;
    @Autowired LeadRepository leads;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired DealEventService events;
    @Autowired EntityManager em;

    final OffsetDateTime t0 = OffsetDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Chat emailChat() {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account("info@westmed.kz")
                .externalChatId("zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz").title("Клиент").build());
    }

    private Chat waChat() {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("Клиент").build());
    }

    private void msg(Chat c, LeadDirection d, OffsetDateTime at, String text) {
        messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(d)
                .type(c.getChannel() == LeadChannel.EMAIL ? ChatMessageType.EMAIL : ChatMessageType.TEXT).body(text).sentAt(at).build());
    }

    private Tender withLead(Chat leadChat) {
        Tender t = requests.createEmpty();
        leads.save(Lead.builder().market(Market.KZ).channel(leadChat.getChannel()).source("whatsapp").externalId("zz" + System.nanoTime())
                .subject("Обращение").status(LeadStatus.CONVERTED).chat(leadChat).privateRequest(t).receivedAt(t0).build());
        return t;
    }

    private List<DealTimelineItem> items(Tender t) {
        em.flush();
        return timeline.forPrivateRequest(t.getId(), null, 50).items();
    }

    private static List<String> texts(List<DealTimelineItem> items) {
        return items.stream().filter(i -> "MESSAGE".equals(i.kind())).map(i -> i.message().getBody()).toList();
    }

    @Test
    void whatsappLead_letterFromDeal_letterAndReplyShown_earlierHidden() {
        Chat wa = waChat();
        Tender t = withLead(wa);
        msg(wa, LeadDirection.IN, t0.plusMinutes(1), "Привет из WhatsApp");
        Chat mail = emailChat();
        msg(mail, LeadDirection.IN, t0.plusMinutes(2), "Старое письмо до сделки");
        OffsetDateTime sent = t0.plusHours(1);
        msg(mail, LeadDirection.OUT, sent, "КП во вложении");
        events.email(t.getId(), mail.getId(), "КП", sent, "zz");
        msg(mail, LeadDirection.IN, sent.plusMinutes(30), "Спасибо, посмотрим");

        assertThat(texts(items(t))).containsExactly("Привет из WhatsApp", "КП во вложении", "Спасибо, посмотрим");
    }

    @Test
    void letterChatIsLeadChat_noDuplicates() {
        Chat mail = emailChat();
        Tender t = withLead(mail);
        OffsetDateTime sent = t0.plusHours(1);
        msg(mail, LeadDirection.OUT, sent, "Ответ");
        events.email(t.getId(), mail.getId(), "Re", sent, "zz");
        assertThat(texts(items(t))).containsExactly("Ответ");
    }

    @Test
    void emailEvents_notShownAsLines() {
        Tender t = requests.createEmpty();
        events.email(t.getId(), emailChat().getId(), "Тема", t0, "zz");
        assertThat(items(t)).noneMatch(i -> "EMAIL".equals(i.kind()));
    }

    @Test
    void removedChat_eventIgnored() {
        Tender t = requests.createEmpty();
        events.email(t.getId(), 987_654_321L, "Тема", t0, "zz");
        assertThat(items(t)).noneMatch(i -> "MESSAGE".equals(i.kind()));
    }
}
