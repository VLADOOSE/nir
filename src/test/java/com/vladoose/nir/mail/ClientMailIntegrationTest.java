package com.vladoose.nir.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientMailReceiveService;
import com.vladoose.nir.service.MailReceiveService;
import com.vladoose.nir.service.mail.ImapTestSupport;
import com.vladoose.nir.service.mail.TestMimes;
import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Ящик info@ сквозь IMAP: «Входящие» и «Отправленные» GreenMail, запись — в nirdb в транзакции теста (откат). */
@MailIntegrationTest
class ClientMailIntegrationTest {

    static final String BOX = "info@westmed.kz";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(true);

    @Autowired ClientMailReceiveService service;
    @Autowired MailReceiveService zakupService;
    @Autowired MailCursorRepository cursorRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired InboundEmailRepository inboundRepository;
    @Autowired EntityManager em;

    GreenMailUser info;
    com.icegreen.greenmail.store.MailFolder sent;

    @BeforeEach
    void setUp() throws Exception {
        MarketContext.set(Market.KZ);
        info = greenMail.setUser(BOX, BOX, "secret");
        sent = greenMail.getManagers().getImapHostManager().createMailbox(info, "Sent");
        for (String f : List.of(MailCursor.INBOX, MailCursor.SENT)) {
            cursorRepository.findById(new MailCursorKey(BOX, f)).ifPresent(cursorRepository::delete);
        }
        cursorRepository.findById(MailCursorKey.inbox("zakup@westmed.kz")).ifPresent(cursorRepository::delete);
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String unique() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    /** Письмо клиента: текст, Excel и PDF вложениями, картинка подписи (inline + Content-ID). */
    static MimeMessage clientLetter(String client) throws Exception {
        MimeMessage m = TestMimes.withAttachments("Айгуль Сапарова <" + client + ">", "Запрос цены",
                "Добрый день! Нужны кушетки 3 шт.", "смета.xlsx", TestMimes.xlsxBytes(), "ТЗ.pdf", new byte[]{4, 5, 6});
        MimeMultipart mixed = (MimeMultipart) m.getContent();
        MimeBodyPart logo = new MimeBodyPart();
        logo.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1, 2, 3}, "image/png")));
        logo.setContentID("<logo1>");
        logo.setDisposition(MimeBodyPart.INLINE);
        logo.setFileName("image001.png");
        mixed.addBodyPart(logo);
        m.setContent(mixed);
        m.setRecipients(Message.RecipientType.TO, BOX);
        m.saveChanges();
        return m;
    }

    @Test
    void clientLetter_becomesLead_ourReplyTakesIt_filesStored_lettersStayUnread() throws Exception {
        String client = unique();
        info.deliver(clientLetter(client));
        sent.appendMessage(TestMimes.letter("West-Med <info@westmed.kz>", client, "colleague@westmed.kz",
                "Re: Запрос цены", "Добрый день! КП во вложении.\n\n> Добрый день! Нужны кушетки 3 шт."), new Flags(), new Date());

        ClientMailReceiveService.Result r = service.poll();

        assertThat(r.stopped()).isNull();
        Chat chat = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, client).orElseThrow();
        List<ChatMessage> msgs = messageRepository.findLatest(chat.getId(), PageRequest.of(0, 10));
        assertThat(msgs).extracting(ChatMessage::getDirection).containsExactlyInAnyOrder(LeadDirection.IN, LeadDirection.OUT);
        ChatMessage in = msgs.stream().filter(m -> m.getDirection() == LeadDirection.IN).findFirst().orElseThrow();
        assertThat(in.getSubject()).isEqualTo("Запрос цены");
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(in.getId())))
                .extracting(ChatAttachmentMeta::fileName).containsExactlyInAnyOrder("смета.xlsx", "ТЗ.pdf");
        ChatMessage out = msgs.stream().filter(m -> m.getDirection() == LeadDirection.OUT).findFirst().orElseThrow();
        assertThat(out.getBody()).isEqualTo("Добрый день! КП во вложении.");
        Lead lead = leadRepository.findByChatIdIn(List.of(chat.getId())).get(0);
        assertThat(lead.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "colleague@westmed.kz")).isEmpty();
        assertThat(ImapTestSupport.seen(BOX, "INBOX", "Запрос цены")).isFalse();
        assertThat(ImapTestSupport.seen(BOX, "Sent", "Re: Запрос цены")).isFalse();
        assertThat(cursorRepository.findById(new MailCursorKey(BOX, MailCursor.INBOX))).isPresent();
        assertThat(cursorRepository.findById(new MailCursorKey(BOX, MailCursor.SENT))).isPresent();
    }

    @Test
    void newsletterServiceAndSiteNotification_doNotStartConversations() throws Exception {
        info.deliver(TestMimes.withHeader(TestMimes.plain("news@shop.kz", "Акция недели zz", "Скидки"),
                "List-Unsubscribe", "<mailto:u@shop.kz>"));
        info.deliver(TestMimes.plain("security@id.mail.ru", "Вход в аккаунт zz", "Новый вход"));
        info.deliver(TestMimes.plain("Сайт <info@westmed.kz>", "Новая заявка — westmed.kz", "Заявка №5"));
        long chatsBefore = chatRepository.count();

        service.poll();

        assertThat(chatRepository.count()).isEqualTo(chatsBefore);
        assertThat(inboundRepository.findAll()).filteredOn(e -> BOX.equals(e.getMailbox())
                        && Set.of("Акция недели zz", "Вход в аккаунт zz", "Новая заявка — westmed.kz").contains(e.getSubject()))
                .extracting(InboundEmail::getSubject).containsExactlyInAnyOrder("Акция недели zz", "Вход в аккаунт zz");
    }

    @Test
    void firstRunWindow_oldLetterIgnored_secondPollNoDuplicates() throws Exception {
        String client = unique();
        greenMail.getManagers().getImapHostManager().getInbox(info).appendMessage(
                TestMimes.plain("Старое <" + client + ">", "Письмо двухнедельной давности", "Давно"), new Flags(),
                Date.from(Instant.now().minus(Duration.ofDays(14))));
        info.deliver(TestMimes.plain("Новое <" + client + ">", "Свежее письмо", "Сейчас"));

        service.poll();
        service.poll();

        Chat chat = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, client).orElseThrow();
        assertThat(messageRepository.findLatest(chat.getId(), PageRequest.of(0, 10)))
                .extracting(ChatMessage::getSubject).containsExactly("Свежее письмо");
        assertThat(leadRepository.findByChatIdIn(List.of(chat.getId()))).hasSize(1);
    }

    @Test
    void zakupAndInfoCursorsAreIndependent() throws Exception {
        GreenMailUser zakup = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        zakup.deliver(TestMimes.plain("s@x.kz", "Прайс поставщика zz", "Текст"));
        String client = unique();
        info.deliver(TestMimes.plain("Клиент <" + client + ">", "Запрос клиента", "Текст"));

        zakupService.poll();
        assertThat(cursorRepository.findById(new MailCursorKey(BOX, MailCursor.INBOX))).isEmpty();

        service.poll();
        assertThat(cursorRepository.findById(MailCursorKey.inbox("zakup@westmed.kz"))).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, client)).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "s@x.kz")).isEmpty();
    }
}
