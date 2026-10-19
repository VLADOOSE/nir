package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ClientMailComposeResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.mail.MailIntegrationTest;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientMailComposeService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Данные окна письма (спека 2026-10-19-client-mail-reply §4, §5, §8). SMTP в этом контексте настроен (GreenMail не нужен). */
@MailIntegrationTest
class ClientMailComposeTest {

    static final String BOX = "info@westmed.kz";

    @Autowired ClientMailComposeService compose;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired LeadRepository leads;
    @Autowired PrivateRequestService requests;
    @Autowired TenderRepository tenders;
    @Autowired ClientOfferService offers;
    @Autowired ClientOfferRepository offerRepository;
    @Autowired DealEventService events;
    @Autowired ReplyTemplateRepository templates;
    @Autowired UserAccountRepository users;
    @Autowired EntityManager em;

    final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        templates.deleteAllInBatch();
        templates.save(ReplyTemplate.builder().market(Market.KZ).title("Подпись").body("С уважением,\n{{сотрудник}}\nWest-Med")
                .role(ReplyTemplateRole.SIGNATURE).position(0).build());
        templates.save(ReplyTemplate.builder().market(Market.KZ).title("Первое письмо").body("Здравствуйте!")
                .role(ReplyTemplateRole.FIRST_REPLY).position(1).build());
        users.save(UserAccount.builder().username("zz-sender").fullName("Иван Петров").role("ROLE_ADMIN").passwordHash("x").build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    private Chat emailChat(String address) {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX).externalChatId(address)
                .title("Айгерим").lastMessageAt(now).build());
    }

    private ChatMessage msg(Chat c, LeadDirection d, OffsetDateTime at, String subject, String body) {
        return messages.save(ChatMessage.builder().chat(c).externalId("<" + UUID.randomUUID() + "@mail.ru>").direction(d)
                .senderName(d == LeadDirection.IN ? "Айгерим" : "Иван Петров").type(ChatMessageType.EMAIL)
                .subject(subject).body(body).sentAt(at).build());
    }

    private ClientMailComposeResponse byChat(Chat c) { return compose.compose(c.getId(), null, null, null, "zz-sender"); }

    @Test
    void chat_replyToLatestLetter_firstLetterUntilWeWrite_signatureWithName() {
        Chat c = emailChat(address());
        msg(c, LeadDirection.IN, now.minusHours(2), "Запрос цены", "Нужны кушетки");
        ClientMailComposeResponse r = byChat(c);
        assertThat(r.enabled()).isTrue();
        assertThat(r.from()).isEqualTo("West-Med <info@westmed.kz>");
        assertThat(r.to()).containsExactly(c.getExternalChatId());
        assertThat(r.reply().replySubject()).isEqualTo("Re: Запрос цены");
        assertThat(r.reply().fromName()).isEqualTo("Айгерим");
        assertThat(r.firstLetter()).isTrue();
        assertThat(r.signature()).isEqualTo("С уважением,\nИван Петров\nWest-Med");
        assertThat(r.templates()).extracting(t -> t.title()).containsExactly("Первое письмо");

        msg(c, LeadDirection.OUT, now.minusHours(1), "Re: Запрос цены", "Ответ");
        ClientMailComposeResponse after = byChat(c);
        assertThat(after.firstLetter()).isFalse();
        assertThat(after.reply().direction()).isEqualTo("OUT");
        assertThat(after.reply().fromName()).isEqualTo("West-Med");
        assertThat(after.reply().fromAddress()).isEqualTo(BOX);
    }

    @Test
    void target_guards() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("x").build());
        assertThatThrownBy(() -> byChat(wa)).isInstanceOf(NotFoundException.class);
        // переписка другого канала с тем же «аккаунтом», что ящик, — тоже не почтовая (проверка канала, а не только ящика)
        Chat sameAccount = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account(BOX)
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("x").build());
        assertThatThrownBy(() -> byChat(sameAccount)).isInstanceOf(NotFoundException.class);
        Chat rf = chats.save(Chat.builder().market(Market.RF).channel(LeadChannel.EMAIL).account(BOX).externalChatId(address())
                .title("x").build());
        assertThatThrownBy(() -> byChat(rf)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> compose.compose(null, null, null, null, "zz-sender"))
                .isInstanceOf(BadRequestException.class).hasMessage("Не указано, кому письмо");
        Chat c = emailChat(address());
        assertThatThrownBy(() -> compose.compose(c.getId(), 1L, null, null, "zz-sender")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void lead_whatsapp_newLetterToContactEmail_lead_email_reply() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("x").build());
        String a = address();
        Lead l = leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).source("whatsapp")
                .externalId("zz" + System.nanoTime()).subject("WhatsApp").contactEmail(a).status(LeadStatus.NEW).chat(wa)
                .receivedAt(now).build());
        ClientMailComposeResponse r = compose.compose(null, l.getId(), null, null, "zz-sender");
        assertThat(r.chatId()).isNull();
        assertThat(r.reply()).isNull();
        assertThat(r.to()).containsExactly(a);
        assertThat(r.firstLetter()).isTrue();
        assertThat(r.leadId()).isEqualTo(l.getId());

        Chat mail = emailChat(address());
        msg(mail, LeadDirection.IN, now, "Вопрос", "Текст");
        l.setChat(mail);
        ClientMailComposeResponse byMail = compose.compose(null, l.getId(), null, null, "zz-sender");
        assertThat(byMail.chatId()).isEqualTo(mail.getId());
        assertThat(byMail.reply()).isNotNull();
    }

    @Test
    void deal_picksChatWithLatestMessage_fromLeadAndDealLetters() {
        Tender t = requests.createEmpty();
        Chat old = emailChat(address());
        old.setLastMessageAt(now.minusDays(2));
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос").status(LeadStatus.CONVERTED).chat(old).privateRequest(t).receivedAt(now.minusDays(3)).build());
        Chat fresh = emailChat(address());
        events.email(t.getId(), fresh.getId(), "КП", now.minusHours(1), "zz-sender");
        ClientMailComposeResponse r = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(r.chatId()).isEqualTo(fresh.getId());
        assertThat(r.tenderId()).isEqualTo(t.getId());
    }

    @Test
    void deal_noChat_addressFromRequest_thenExistingChatOfThatAddress() {
        Tender t = requests.createEmpty();
        String a = address();
        t.setContactEmail(a);
        tenders.saveAndFlush(t);
        ClientMailComposeResponse r = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(r.chatId()).isNull();
        assertThat(r.to()).containsExactly(a);

        Chat c = emailChat(a);
        ClientMailComposeResponse withChat = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(withChat.chatId()).isEqualTo(c.getId());
    }

    @Test
    void offer_subjectWithEnDash_preselected_offersOfDeal() {
        Tender t = requests.createEmpty();
        ClientOffer o1 = offers.create("zz-sender");
        o1.setTenderId(t.getId());
        ClientOffer o2 = offers.create("zz-sender");
        o2.setTenderId(t.getId());
        offerRepository.saveAllAndFlush(List.of(o1, o2));
        ClientMailComposeResponse r = compose.compose(null, null, null, o1.getId(), "zz-sender");
        assertThat(r.offerId()).isEqualTo(o1.getId());
        assertThat(r.tenderId()).isEqualTo(t.getId());
        assertThat(r.offers()).extracting(ClientMailComposeResponse.OfferRef::id).containsExactlyInAnyOrder(o1.getId(), o2.getId());
        assertThat(r.newSubject()).matches("Коммерческое предложение № " + o1.getNumber() + " от \\d{2}\\.\\d{2}\\.\\d{4} – West-Med")
                .doesNotContain("—");
        assertThat(r.offers()).allSatisfy(x -> assertThat(x.fileName()).endsWith(".pdf"));
    }

    @Test
    void rfMarket_disabledWithReason() {
        MarketContext.set(Market.RF);
        Tender t = requests.createEmpty();
        ClientMailComposeResponse r = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(r.enabled()).isFalse();
        assertThat(r.reason()).isEqualTo("Письма клиентам — только на рынке KZ (ящик info@westmed.kz)");
    }
}
