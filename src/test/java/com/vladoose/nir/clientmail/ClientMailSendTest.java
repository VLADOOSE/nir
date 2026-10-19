package com.vladoose.nir.clientmail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ClientMailController;
import com.vladoose.nir.dto.request.ClientMailSendRequest;
import com.vladoose.nir.dto.response.ClientMailSendResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.mail.MailIntegrationTest;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientMailReceiveService;
import com.vladoose.nir.service.ClientMailSendService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Письмо клиенту сквозь SMTP и IMAP GreenMail (спека 2026-10-19-client-mail-reply §3, §9). */
@MailIntegrationTest
class ClientMailSendTest {

    static final String BOX = "info@westmed.kz";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(BOX, "secret"))
            .withPerMethodLifecycle(true);

    @Autowired ClientMailSendService service;
    @Autowired ClientMailController controller;
    @Autowired ClientMailReceiveService intake;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired LeadRepository leads;
    @Autowired UserAccountRepository users;
    @Autowired MailCursorRepository cursors;
    @Autowired ReplyTemplateRepository templates;
    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferService offers;
    @Autowired ClientOfferRepository offerRepository;
    @Autowired DealEventRepository events;
    @Autowired WebApplicationContext wac;
    @Autowired EntityManager em;

    String client;
    Chat chat;
    ChatMessage letterIn;
    Lead lead;
    com.icegreen.greenmail.store.MailFolder sentFolder;

    @BeforeEach
    void setUp() throws Exception {
        MarketContext.set(Market.KZ);
        GreenMailUser info = greenMail.getManagers().getUserManager().getUser(BOX);
        sentFolder = greenMail.getManagers().getImapHostManager().createMailbox(info, "Sent");
        for (String f : List.of(MailCursor.INBOX, MailCursor.SENT)) {
            cursors.findById(new MailCursorKey(BOX, f)).ifPresent(cursors::delete);
        }
        templates.deleteAllInBatch();   // подпись — своя: тест не зависит от правок оператора (CLAUDE.md §14)
        templates.save(ReplyTemplate.builder().market(Market.KZ).title("Подпись").body("С уважением,\n{{сотрудник}}\nWest-Med")
                .role(ReplyTemplateRole.SIGNATURE).position(0).build());
        users.save(UserAccount.builder().username("zz-sender").fullName("Иван Петров").role("ROLE_ADMIN").passwordHash("x").build());
        client = "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz";
        chat = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX).externalChatId(client)
                .title("Айгерим").build());
        letterIn = messages.save(ChatMessage.builder().chat(chat).externalId("<client-" + UUID.randomUUID() + "@mail.ru>")
                .direction(LeadDirection.IN).senderName("Айгерим").type(ChatMessageType.EMAIL).subject("Запрос цены")
                .body("Нужны кушетки 3 шт.").sentAt(OffsetDateTime.now().minusHours(1)).build());
        lead = leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос цены").contactName("Айгерим").contactEmail(client).status(LeadStatus.NEW).chat(chat)
                .receivedAt(OffsetDateTime.now().minusHours(1)).build());
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String token() { return UUID.randomUUID().toString(); }

    private ClientMailSendRequest reply(String text) {
        return new ClientMailSendRequest(chat.getId(), letterIn.getId(), null, null, text, null, null, List.of(), token());
    }

    private ClientMailSendRequest letter(String to, String subject) {
        return new ClientMailSendRequest(null, null, to, subject, "Текст", null, null, List.of(), token());
    }

    private MimeMessage only() {
        MimeMessage[] got = greenMail.getReceivedMessages();
        assertThat(got).hasSize(1);
        return got[0];
    }

    private static String text(Part p) throws Exception {
        if (p.isMimeType("text/plain")) return ((String) p.getContent()).replace("\r\n", "\n");
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String t = text(mp.getBodyPart(i));
                if (!t.isEmpty()) return t;
            }
        }
        return "";
    }

    private static List<String> fileNames(Part p) throws Exception {
        List<String> out = new ArrayList<>();
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) out.addAll(fileNames(mp.getBodyPart(i)));
        } else if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            out.add(MimeUtility.decodeText(p.getFileName()));
        }
        return out;
    }

    @Test
    void replyInChat_deliveredInThread_recorded_leadInWork() throws Exception {
        ClientMailSendResponse r = service.send(reply("Здравствуйте!\n\nКП пришлём сегодня."), List.of(), "zz-sender");

        MimeMessage m = only();
        assertThat(m.getSubject()).isEqualTo("Re: Запрос цены");
        assertThat(m.getHeader("In-Reply-To", null)).isEqualTo(letterIn.getExternalId());
        assertThat(m.getRecipients(Message.RecipientType.TO)).extracting(Object::toString).containsExactly(client);
        assertThat(text(m)).startsWith("Здравствуйте!\n\nКП пришлём сегодня.\n\nС уважением,\nИван Петров\nWest-Med")
                .contains("Айгерим <" + client + "> пишет:").contains("> Нужны кушетки 3 шт.");
        assertThat(r.recorded()).isTrue();
        assertThat(r.chatIds()).containsExactly(chat.getId());
        ChatMessage out = messages.findByChatIdAndExternalId(chat.getId(), m.getMessageID()).orElseThrow();
        assertThat(out.getSenderName()).isEqualTo("Иван Петров");
        assertThat(out.getBody()).doesNotContain("пишет:");
        em.flush();
        em.refresh(lead);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
    }

    @Test
    void sentCopyReadByIntake_isDuplicate() throws Exception {
        service.send(reply("Ответ"), List.of(), "zz-sender");
        MimeMessage m = only();
        sentFolder.appendMessage(m, new Flags(Flags.Flag.SEEN), new Date());
        em.flush();

        ClientMailReceiveService.Result r = intake.poll();

        assertThat(r.stopped()).isNull();
        assertThat(messages.findLatestByDirection(chat.getId(), LeadDirection.OUT, PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void newTopicInChat_needsSubject_noQuoteNoThread() throws Exception {
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(chat.getId(), null, null, " ", "Текст", null, null,
                List.of(), token()), List.of(), "zz-sender")).isInstanceOf(BadRequestException.class).hasMessage("Укажите тему письма");
        service.send(new ClientMailSendRequest(chat.getId(), null, null, "Счёт на оплату", "Текст", null, null, List.of(), token()),
                List.of(), "zz-sender");
        MimeMessage m = only();
        assertThat(m.getSubject()).isEqualTo("Счёт на оплату");
        assertThat(m.getHeader("In-Reply-To")).isNull();
        assertThat(text(m)).doesNotContain("пишет:");
    }

    @Test
    void replyToLetterWithoutMessageId_noThreadHeaders() throws Exception {   // Review Focus 5
        ChatMessage noId = messages.save(ChatMessage.builder().chat(chat).externalId("uid:inbox:7:42").direction(LeadDirection.IN)
                .senderName("Айгерим").type(ChatMessageType.EMAIL).subject("Вопрос").body("Есть в наличии?")
                .sentAt(OffsetDateTime.now()).build());
        service.send(new ClientMailSendRequest(chat.getId(), noId.getId(), null, null, "Да", null, null, List.of(), token()),
                List.of(), "zz-sender");
        MimeMessage m = only();
        assertThat(m.getSubject()).isEqualTo("Re: Вопрос");
        assertThat(m.getHeader("In-Reply-To")).isNull();
        assertThat(m.getHeader("References")).isNull();
        assertThat(text(m)).contains("> Есть в наличии?");
    }

    @Test
    void newLetter_twoAddresses_chatsAndAttachment() throws Exception {
        String a = "zz-a" + UUID.randomUUID().toString().substring(0, 6) + "@clinic.kz";
        MockMultipartFile f = new MockMultipartFile("files", "Смета.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
        ClientMailSendResponse r = service.send(new ClientMailSendRequest(null, null, a + ", " + client, "Счёт", "Текст", null,
                null, List.of(), token()), List.of(f), "zz-sender");
        assertThat(r.chatIds()).hasSize(2).contains(chat.getId());
        MimeMessage[] got = greenMail.getReceivedMessages();   // GreenMail кладёт копию каждому адресату
        assertThat(got).isNotEmpty();
        assertThat(fileNames(got[0])).containsExactly("Смета.xlsx");
    }

    @Test
    void ownDomainRecipient_sentWithoutChat() {   // Review Focus 4
        ClientMailSendResponse r = service.send(letter("manager@westmed.kz", "Вопрос"), List.of(), "zz-sender");
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(r.recorded()).isTrue();
        assertThat(r.chatIds()).isEmpty();
        assertThat(chats.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "manager@westmed.kz")).isEmpty();
    }

    @Test
    void offerFromDeal_pdfAttached_offerSent_dealEvent() throws Exception {
        Tender t = requests.createEmpty();
        ClientOffer o = offers.create("zz-sender");
        o.setTenderId(t.getId());
        offerRepository.saveAndFlush(o);

        ClientMailSendResponse r = service.send(new ClientMailSendRequest(chat.getId(), letterIn.getId(), null, null,
                "КП во вложении", null, t.getId(), List.of(new ClientMailSendRequest.OfferAttachment(o.getId(), "PDF")), token()),
                List.of(), "zz-sender");

        assertThat(fileNames(only())).singleElement().asString().startsWith("КП № " + o.getNumber()).endsWith(".pdf");
        assertThat(r.offersSent()).containsExactly(o.getId());
        em.flush();
        em.refresh(o);
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(events.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(t.getId(), DealEventType.EMAIL))
                .singleElement().extracting(DealEvent::getRefId).isEqualTo(chat.getId());
    }

    @Test
    void offerOfAnotherDeal_400_nothingSent() {
        Tender t1 = requests.createEmpty();
        Tender t2 = requests.createEmpty();
        ClientOffer o = offers.create("zz-sender");
        o.setTenderId(t1.getId());
        offerRepository.saveAndFlush(o);
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(chat.getId(), letterIn.getId(), null, null, "Текст", null,
                t2.getId(), List.of(new ClientMailSendRequest.OfferAttachment(o.getId(), "PDF")), token()), List.of(), "zz-sender"))
                .isInstanceOf(BadRequestException.class).hasMessageEndingWith("— не из этой заявки");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void sameTokenTwice_secondIs409_oneLetterDelivered() {   // Review Focus 1
        ClientMailSendRequest req = reply("Текст");
        service.send(req, List.of(), "zz-sender");
        assertThatThrownBy(() -> service.send(req, List.of(), "zz-sender")).isInstanceOf(ConflictException.class);
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    void validation_beforeSending_nothingSent() {
        assertBad(reply("  "), List.of(), "Напишите текст письма");
        assertBad(letter("не-адрес", "Тема"), List.of(), "Неверный адрес почты: «не-адрес»");
        assertBad(letter("", "Тема"), List.of(), "Укажите адрес получателя");
        String eleven = IntStream.range(0, 11).mapToObj(i -> "zz" + i + "@clinic.kz").collect(Collectors.joining(", "));
        assertBad(letter(eleven, "Тема"), List.of(), "Адресов не больше 10");
        Chat other = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX)
                .externalChatId("zz-o" + System.nanoTime() + "@clinic.kz").title("x").build());
        ChatMessage foreign = messages.save(ChatMessage.builder().chat(other).externalId("<f" + System.nanoTime() + "@x>")
                .direction(LeadDirection.IN).type(ChatMessageType.EMAIL).subject("x").body("x").sentAt(OffsetDateTime.now()).build());
        assertBad(new ClientMailSendRequest(chat.getId(), foreign.getId(), null, null, "Текст", null, null, List.of(), token()),
                List.of(), "Письмо, на которое вы отвечаете, — не из этой переписки");
        List<MultipartFile> elevenFiles = IntStream.range(0, 11)
                .mapToObj(i -> (MultipartFile) new MockMultipartFile("files", "f" + i + ".txt", "text/plain", new byte[]{1})).toList();
        assertBad(reply("Текст"), elevenFiles, "Файлов не больше 10");
        byte[] big = new byte[11 * 1024 * 1024];
        assertBad(reply("Текст"), List.of(new MockMultipartFile("files", "a.bin", "application/octet-stream", big),
                new MockMultipartFile("files", "b.bin", "application/octet-stream", big)), "Письмо больше 20 МБ — уберите часть файлов");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void foreignChats_404_rfMarket_400() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("x").build());
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(wa.getId(), null, null, "Тема", "Текст", null, null,
                List.of(), token()), List.of(), "zz-sender")).isInstanceOf(NotFoundException.class);
        Chat rf = chats.save(Chat.builder().market(Market.RF).channel(LeadChannel.EMAIL).account(BOX)
                .externalChatId("zz-rf" + System.nanoTime() + "@clinic.kz").title("x").build());
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(rf.getId(), null, null, "Тема", "Текст", null, null,
                List.of(), token()), List.of(), "zz-sender")).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertBad(letter(client, "Тема"), List.of(), "Письма клиентам — только на рынке KZ (ящик info@westmed.kz)");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "USER")
    void operator_403() {
        assertThatThrownBy(() -> controller.send(reply("Текст"), List.of())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void multipartLetterPartBinds_overHttp() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        String json = "{\"chatId\":" + chat.getId() + ",\"replyToMessageId\":" + letterIn.getId()
                + ",\"text\":\"Через HTTP\",\"offers\":[],\"token\":\"" + token() + "\"}";
        mvc.perform(multipart("/api/client-mail/send")
                        .file(new MockMultipartFile("letter", "", "application/json", json.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain", new byte[]{1}))
                        .header("X-Market", "KZ")
                        .with(user("zz-sender").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(true));
        MarketContext.set(Market.KZ);   // перехватчик рынка почистил контекст потока теста (CLAUDE.md §14)
        assertThat(fileNames(only())).containsExactly("a.txt");
    }

    private void assertBad(ClientMailSendRequest r, List<MultipartFile> files, String message) {
        assertThatThrownBy(() -> service.send(r, files, "zz-sender")).isInstanceOf(BadRequestException.class).hasMessage(message);
    }
}
