package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.EmailChatWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ClientMailWriterTest {

    static final String BOX = "info@westmed.kz";

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired MailCursorRepository cursorRepo;
    @Autowired EmailChatWriter chats;
    @Autowired ChatRepository chatRepository;

    ClientMailWriter writer;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        writer = new ClientMailWriter(inboundRepo, cursorRepo, chats, new ClientMailSettings(true, "127.0.0.1", 3143,
                BOX, "secret", "imap", "KZ", 7, 25, "*.mail.ru,2gis.ru,2gis.com,ecc.kz,goszakup.gov.kz",
                "info@westmed.kz", "zakup@westmed.kz"));
        cursorRepo.findById(MailCursorKey.inbox(BOX)).ifPresent(cursorRepo::delete);
        cursorRepo.findById(new MailCursorKey(BOX, MailCursor.SENT)).ifPresent(cursorRepo::delete);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String unique(String domain) { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@" + domain; }

    private InboundEmail row(ParsedMail m) {
        return inboundRepo.findAll().stream()
                .filter(e -> BOX.equals(e.getMailbox()) && m.messageId().equals(e.getMessageId())).findFirst().orElseThrow();
    }

    private long cursor(String folder) {
        return cursorRepo.findById(new MailCursorKey(BOX, folder)).orElseThrow().getLastUid();
    }

    @Test
    void clientLetter_goesToConversation_inboxCursorMoves() {
        String from = unique("clinic.kz");

        ClientMailWriter.Result r = writer.write(mail().uid(11).from("Айгуль <" + from + ">").subject("Запрос цены")
                .text("Нужны кушетки").build(), MailFolder.INBOX, 5);

        assertThat(r.kind()).isEqualTo(ClientMailWriter.Kind.CONVERSATION);
        assertThat(r.createdLeadId()).isNotNull();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, from)).isPresent();
        assertThat(cursor(MailCursor.INBOX)).isEqualTo(11);
        assertThat(cursorRepo.findById(new MailCursorKey(BOX, MailCursor.SENT))).isEmpty();
    }

    @Test
    void siteNotification_skipped_noRow_cursorMoves() {
        ParsedMail m = mail().uid(12).from("Сайт <info@westmed.kz>").subject("Новая заявка — westmed.kz").build();

        assertThat(writer.write(m, MailFolder.INBOX, 5).kind()).isEqualTo(ClientMailWriter.Kind.SKIPPED);
        assertThat(inboundRepo.existsByMailboxAndMessageId(BOX, m.messageId())).isFalse();
        assertThat(cursor(MailCursor.INBOX)).isEqualTo(12);
    }

    /** Возврат, автоответ, рассылка, служебное, своё — строкой «Входящих» ящика info@, без уведомления в Telegram (§4). */
    @Test
    void reportsNewslettersServiceAndOwnDomain_goToInboxJournal_withoutTelegram() {
        record Case(ParsedMail m, InboundType type) {}
        List<Case> cases = List.of(
                new Case(mail().uid(21).from("MAILER-DAEMON@corp.mail.ru").contentType("multipart/report; report-type=delivery-status")
                        .bounce("a@b.kz", "5.1.1", "550", "Запрос", "failed").build(), InboundType.BOUNCE),
                new Case(mail().uid(22).from("anna@clinic.kz").header("Auto-Submitted", "auto-replied").build(), InboundType.AUTO_REPLY),
                new Case(mail().uid(23).from("news@shop.kz").header("List-Unsubscribe", "<mailto:u@shop.kz>").build(), InboundType.UNMATCHED),
                new Case(mail().uid(24).from("security@id.mail.ru").build(), InboundType.UNMATCHED),
                new Case(mail().uid(25).from("buh@westmed.kz").build(), InboundType.UNMATCHED));

        for (Case c : cases) {
            assertThat(writer.write(c.m(), MailFolder.INBOX, 5).kind()).isEqualTo(ClientMailWriter.Kind.INBOUND);
            InboundEmail e = row(c.m());
            assertThat(e.getType()).isEqualTo(c.type());
            assertThat(e.getMarket()).isEqualTo(Market.KZ);
            assertThat(e.getNotifyStatus()).isNull();
        }
        assertThat(cursor(MailCursor.INBOX)).isEqualTo(25);
    }

    @Test
    void inboxJournalDuplicateByMessageId_oneRow() {
        ParsedMail m = mail().uid(26).from("news@shop.kz").header("Precedence", "bulk").build();
        writer.write(m, MailFolder.INBOX, 5);

        assertThat(writer.write(m, MailFolder.INBOX, 5).kind()).isEqualTo(ClientMailWriter.Kind.DUPLICATE);
        assertThat(inboundRepo.findAll().stream().filter(e -> m.messageId().equals(e.getMessageId()))).hasSize(1);
    }

    @Test
    void sentReplyGoesToEachExternalRecipient_notToColleaguesOrServices() {
        String a = unique("clinic.kz");
        String b = unique("hospital.kz");
        ParsedMail m = mail().uid(31).from("West-Med <info@westmed.kz>").subject("Re: Запрос").text("КП во вложении")
                .to(a, "colleague@westmed.kz", b, "support@corp.mail.ru", "noreply@2gis.ru").build();

        ClientMailWriter.Result r = writer.write(m, MailFolder.SENT, 9);

        assertThat(r.kind()).isEqualTo(ClientMailWriter.Kind.OUTGOING);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, a)).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, b)).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "colleague@westmed.kz")).isEmpty();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "support@corp.mail.ru")).isEmpty();
        assertThat(cursor(MailCursor.SENT)).isEqualTo(31);
        assertThat(cursorRepo.findById(MailCursorKey.inbox(BOX))).isEmpty();
    }

    @Test
    void sentToColleaguesOnly_skipped() {
        ParsedMail m = mail().uid(32).from("info@westmed.kz").subject("Акт").to("buh@westmed.kz").build();

        assertThat(writer.write(m, MailFolder.SENT, 9).kind()).isEqualTo(ClientMailWriter.Kind.SKIPPED);
        assertThat(cursor(MailCursor.SENT)).isEqualTo(32);
    }

    @Test
    void brokenLetter_shortRowAndCursorOfItsFolder() {
        writer.writeBroken(new BrokenMail(41, "<zz-broken-" + UUID.randomUUID() + "@x.kz>", "a@b.kz", "Тема",
                OffsetDateTime.now(), "MessagingException"), MailFolder.SENT, 9);

        assertThat(inboundRepo.findAll()).anySatisfy(e -> {
            assertThat(e.getMailbox()).isEqualTo(BOX);
            assertThat(e.getImapUid()).isEqualTo(41L);
            assertThat(e.getExcerpt()).isEqualTo("Письмо из «Отправленных» не удалось разобрать (MessagingException) — откройте его в почте");
        });
        assertThat(cursor(MailCursor.SENT)).isEqualTo(41);
    }
}
