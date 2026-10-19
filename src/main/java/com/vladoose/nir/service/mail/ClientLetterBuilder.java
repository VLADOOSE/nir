package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Сборка письма клиенту (спека 2026-10-19-client-mail-reply §3.2) — чистые функции. Тема ответа — «Re:» без второго «Re:»,
 * текст — текст → подпись → у ответа строка цитаты «ДД.ММ.ГГГГ ЧЧ:ММ, Имя <адрес> пишет:» (часы рынка) и текст того
 * письма с «> ». Строка цитаты подходит под EmailReplyText.ATTRIBUTION: ответ клиента над нашей цитатой приём режет как
 * обычно. Длинного тире здесь нет (спека §2 п. 4).
 */
public final class ClientLetterBuilder {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern REPLY_PREFIX = Pattern.compile("(?iu)^\\s*(re|ответ)\\s*:");

    private ClientLetterBuilder() {}

    /** «Re: тема» без второго «Re:»; без темы — «Re: письмо от ДД.ММ.ГГГГ» (дата письма по часам рынка). */
    public static String replySubject(String parentSubject, OffsetDateTime parentSentAt, ZoneId zone) {
        String s = parentSubject == null ? "" : parentSubject.strip();
        if (s.isEmpty()) return "Re: письмо от " + DAY.format(parentSentAt.atZoneSameInstant(zone));
        return REPLY_PREFIX.matcher(s).find() ? s : "Re: " + s;
    }

    /** Текст письма: текст → подпись → у ответа строка цитаты и цитата. Переводы строк — \n. */
    public static String body(String text, String signature, ClientLetter.Quote quote, ZoneId zone) {
        StringBuilder b = new StringBuilder(normalize(text).strip());
        String sig = normalize(signature).strip();
        if (!sig.isEmpty()) b.append("\n\n").append(sig);
        if (quote != null) b.append("\n\n").append(attribution(quote, zone)).append('\n').append(quoted(quote.body()));
        return b.toString();
    }

    static String attribution(ClientLetter.Quote q, ZoneId zone) {
        String when = WHEN.format(q.sentAt().atZoneSameInstant(zone));
        String who = q.name() == null || q.name().isBlank() ? q.address() : q.name().strip() + " <" + q.address() + ">";
        return when + ", " + who + " пишет:";
    }

    static String quoted(String body) {
        String t = normalize(body).strip();
        if (t.isEmpty()) return ">";
        return t.lines().map(l -> l.isEmpty() ? ">" : "> " + l).collect(Collectors.joining("\n"));
    }

    /** Наш Message-ID — заранее: он же ключ сообщения в переписке (копию из «Отправленных» приём отсечёт как повтор). */
    public static String messageId(String mailbox) {
        String domain = mailbox.substring(mailbox.indexOf('@') + 1);
        return "<ais." + UUID.randomUUID() + "@" + domain + ">";
    }

    /** Ключ сообщения переписки — Message-ID (не «sha256:…» длинного и не «uid:…» письма без него). */
    public static boolean isMessageId(String key) {
        return key != null && key.startsWith("<") && key.endsWith(">") && key.indexOf('@') > 0;
    }

    /** MIME: простой текст UTF-8, вложения — multipart, имена кодируются; saveChanges наш Message-ID не меняет. */
    public static MimeMessage build(Session session, ClientLetter l, ZoneId zone) throws MessagingException {
        MimeMessage m = new FixedIdMessage(session, l.messageId());
        boolean multipart = l.attachments() != null && !l.attachments().isEmpty();
        MimeMessageHelper h = new MimeMessageHelper(m, multipart, "UTF-8");
        h.setEncodeFilenames(true);
        try {
            h.setFrom(new InternetAddress(l.fromAddress(), l.fromName(), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Не собрался адрес «От»", e);
        }
        h.setTo(l.to().toArray(String[]::new));
        h.setSubject(l.subject());
        h.setSentDate(new Date());
        h.setText(body(l.text(), l.signature(), l.quote(), zone), false);
        if (l.inReplyTo() != null) {
            m.setHeader("In-Reply-To", l.inReplyTo());
            m.setHeader("References", l.inReplyTo());
        }
        if (multipart) {
            for (ClientLetter.Attachment a : l.attachments()) {
                h.addAttachment(a.fileName(), new ByteArrayResource(a.bytes()), a.contentType());
            }
        }
        m.saveChanges();
        return m;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** MimeMessage, у которого saveChanges не меняет Message-ID (JavaMail иначе ставит свой при каждом saveChanges). */
    static final class FixedIdMessage extends MimeMessage {
        private final String id;

        FixedIdMessage(Session session, String id) {
            super(session);
            this.id = id;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", id);
        }
    }
}
