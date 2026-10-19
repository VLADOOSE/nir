package com.vladoose.nir.service.mail;

import com.vladoose.nir.util.EmailReplyText;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Письмо клиенту с info@ (спека 2026-10-19-client-mail-reply §3.2). */
class ClientLetterBuilderTest {

    static final ZoneId ORAL = ZoneId.of("Asia/Oral");
    static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-19T05:15:00Z");   // 10:15 по Уральску
    final Session session = Session.getInstance(new Properties());

    @Test
    void replySubject_reOnce_emptySubjectGetsDate() {
        assertThat(ClientLetterBuilder.replySubject("Запрос цены", AT, ORAL)).isEqualTo("Re: Запрос цены");
        assertThat(ClientLetterBuilder.replySubject("RE: Запрос", AT, ORAL)).isEqualTo("RE: Запрос");
        assertThat(ClientLetterBuilder.replySubject("Ответ: Запрос", AT, ORAL)).isEqualTo("Ответ: Запрос");
        assertThat(ClientLetterBuilder.replySubject("Return policy", AT, ORAL)).isEqualTo("Re: Return policy");
        assertThat(ClientLetterBuilder.replySubject("  ", AT, ORAL)).isEqualTo("Re: письмо от 19.10.2026");
        assertThat(ClientLetterBuilder.replySubject(null, AT, ORAL)).isEqualTo("Re: письмо от 19.10.2026");
    }

    @Test
    void attributionUsesMarketZone() {   // Review Focus 3
        OffsetDateTime late = OffsetDateTime.parse("2026-10-19T20:30:00Z");   // 01:30 20.10 по Уральску (+05)
        assertThat(ClientLetterBuilder.attribution(new ClientLetter.Quote(late, "Айгерим Касымова", "a@clinic.kz", "x"), ORAL))
                .isEqualTo("20.10.2026 01:30, Айгерим Касымова <a@clinic.kz> пишет:");
        assertThat(ClientLetterBuilder.attribution(new ClientLetter.Quote(late, null, "a@clinic.kz", "x"), ORAL))
                .isEqualTo("20.10.2026 01:30, a@clinic.kz пишет:");
    }

    @Test
    void body_textSignatureQuote_crlfNormalized() {
        ClientLetter.Quote q = new ClientLetter.Quote(AT, "Айгерим", "a@clinic.kz", "Нужны кушетки.\r\n\r\nСпасибо");
        String b = ClientLetterBuilder.body("Здравствуйте!\r\n\r\nКП во вложении.  \n", "С уважением,\nИван Петров\nWest-Med", q, ORAL);
        assertThat(b).isEqualTo("Здравствуйте!\n\nКП во вложении.\n\nС уважением,\nИван Петров\nWest-Med\n\n"
                + "19.10.2026 10:15, Айгерим <a@clinic.kz> пишет:\n> Нужны кушетки.\n>\n> Спасибо");
    }

    @Test
    void body_withoutSignatureAndQuote_isText() {
        assertThat(ClientLetterBuilder.body(" Текст ", "  ", null, ORAL)).isEqualTo("Текст");
    }

    @Test
    void ourQuote_isCutByOurReplyParser() {
        // клиент ответит над нашей цитатой — приём режет его ответ по нашей же строке цитаты
        String b = ClientLetterBuilder.body("Ответ клиента", "", new ClientLetter.Quote(AT, "West-Med", "info@westmed.kz", "Наш вопрос"), ORAL);
        assertThat(EmailReplyText.cutQuote(b).strip()).isEqualTo("Ответ клиента");
    }

    @Test
    void messageId_ofMailboxDomain() {
        assertThat(ClientLetterBuilder.messageId("info@westmed.kz")).matches("<ais\\.[0-9a-f-]{36}@westmed\\.kz>");
        assertThat(ClientLetterBuilder.isMessageId("<a.b@mail.ru>")).isTrue();
        assertThat(ClientLetterBuilder.isMessageId("sha256:abc")).isFalse();
        assertThat(ClientLetterBuilder.isMessageId("uid:inbox:7:42")).isFalse();
        assertThat(ClientLetterBuilder.isMessageId(null)).isFalse();
    }

    @Test
    void build_headersFromToThreadAndAttachments_messageIdSurvivesSaveChanges() throws Exception {
        String id = ClientLetterBuilder.messageId("info@westmed.kz");
        ClientLetter l = new ClientLetter("West-Med", "info@westmed.kz", List.of("a@clinic.kz", "b@clinic.kz"),
                "Re: Запрос", "Текст", "С уважением,\nWest-Med", null, "<client-1@mail.ru>", id,
                List.of(new ClientLetter.Attachment("КП № 24 от 19.10.2026 – West-Med.pdf", new byte[]{1, 2}, "application/pdf")));

        MimeMessage m = ClientLetterBuilder.build(session, l, ORAL);

        assertThat(m.getMessageID()).isEqualTo(id);
        assertThat(m.getHeader("In-Reply-To", null)).isEqualTo("<client-1@mail.ru>");
        assertThat(m.getHeader("References", null)).isEqualTo("<client-1@mail.ru>");
        InternetAddress from = (InternetAddress) m.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("info@westmed.kz");
        assertThat(from.getPersonal()).isEqualTo("West-Med");
        assertThat(m.getRecipients(Message.RecipientType.TO)).extracting(Object::toString).containsExactly("a@clinic.kz", "b@clinic.kz");
        assertThat(m.getSubject()).isEqualTo("Re: Запрос");
        assertThat(fileNames(m)).containsExactly("КП № 24 от 19.10.2026 – West-Med.pdf");
        m.saveChanges();   // как JavaMailSender перед отправкой
        assertThat(m.getMessageID()).isEqualTo(id);
    }

    @Test
    void build_withoutParentId_noThreadHeaders_plainText() throws Exception {
        ClientLetter l = new ClientLetter("West-Med", "info@westmed.kz", List.of("a@clinic.kz"), "Счёт", "Текст", "",
                null, null, ClientLetterBuilder.messageId("info@westmed.kz"), List.of());
        MimeMessage m = ClientLetterBuilder.build(session, l, ORAL);
        assertThat(m.getHeader("In-Reply-To")).isNull();
        assertThat(m.getHeader("References")).isNull();
        assertThat(m.isMimeType("text/plain")).isTrue();
        assertThat(((String) m.getContent())).isEqualTo("Текст");
    }

    /**
     * Message-ID и тема прежнего письма берутся из базы как пришли (тема — после декодирования RFC 2047, в ней бывает перевод
     * строки): перевод строки в них дописал бы письму на проводе свой заголовок. Ключ с пробелом или переводом строки — не
     * Message-ID (заголовков цепочки нет), тема — в одну строку. Проверка — по письму, записанному в байты и прочитанному заново.
     */
    @Test
    void headerInjectionFromStoredMail_neutralized() throws Exception {
        assertThat(ClientLetterBuilder.isMessageId("<a@b.kz>\r\nBcc: <evil@x.kz>")).isFalse();
        assertThat(ClientLetterBuilder.isMessageId("<a b@c.kz>")).isFalse();
        assertThat(ClientLetterBuilder.isMessageId("<CAF=x_Y+z.1@mail.gmail.com>")).isTrue();
        // тема прежнего письма (из базы, после RFC 2047) — в одну строку и в переписке АИС, и в письме
        assertThat(ClientLetterBuilder.replySubject("Запрос\r\nцены", AT, ORAL)).isEqualTo("Re: Запрос цены");
        ClientLetter l = new ClientLetter("West-Med", "info@westmed.kz", List.of("a@clinic.kz"),
                "Re: Order\r\nBcc: evil@x.kz", "Текст", "", null, "<a@b.kz>\r\nX-Evil: 1",
                ClientLetterBuilder.messageId("info@westmed.kz"), List.of());

        MimeMessage back = wire(ClientLetterBuilder.build(session, l, ORAL));

        assertThat(back.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(back.getHeader("X-Evil")).isNull();
        assertThat(back.getHeader("In-Reply-To")).isNull();
        assertThat(back.getSubject()).isEqualTo("Re: Order Bcc: evil@x.kz");
    }

    MimeMessage wire(MimeMessage m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return new MimeMessage(session, new ByteArrayInputStream(out.toByteArray()));
    }

    static List<String> fileNames(Part p) throws Exception {
        List<String> out = new ArrayList<>();
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) out.addAll(fileNames(mp.getBodyPart(i)));
        } else if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            out.add(MimeUtility.decodeText(p.getFileName()));
        }
        return out;
    }
}
