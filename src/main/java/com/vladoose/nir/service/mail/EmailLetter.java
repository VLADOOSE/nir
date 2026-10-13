package com.vladoose.nir.service.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Письмо ящика info@ для переписки (спека 2026-10-13-info-mail-leads §2.3): ключ повтора, тема, текст без цитаты
 * (вся цепочка и так видна в ленте), время, отправитель, файлы. Собирается в транзакции записи письма
 * (ClientMailWriter) — из уже разобранного ParsedMail, сети здесь нет.
 *
 * @param key  ключ сообщения в переписке: Message-ID (длиннее 100 символов — его SHA-256), без него — папка и номер
 * @param body текст без цитаты; письмо целиком из цитаты и пересланное письмо — текст как есть
 */
public record EmailLetter(String key, String subject, String body, OffsetDateTime sentAt,
                          String fromAddress, String fromName, List<MailFile> files) {

    /** Длина chat_message.external_id. */
    static final int KEY_MAX = 100;

    /** Тема пересылки: «Fwd:», «FW:», «Пересл.:», «ПЕРЕСЛ:» — в начале темы («Re: Fwd:» — уже ответ). */
    private static final Pattern FORWARD_SUBJECT = Pattern.compile("(?iu)^\\s*(fwd?|пересл\\.?)\\s*:");

    /** Маркер пересланного письма в тексте (Gmail, Mail.ru, Яндекс, Thunderbird, Apple Mail). */
    private static final Pattern FORWARD_MARKER = Pattern.compile("(?imu)^[ \\t]*-*[ \\t]*(forwarded message"
            + "|пересылаемое сообщение|пересланное сообщение|begin forwarded message|начало переадресованного сообщения)");

    public static EmailLetter of(ParsedMail m, MailFolder folder, long uidValidity) {
        String reply = MailText.replyText(m);
        // пересланное письмо не режем: граница цитаты («From:», «От: … Отправлено:») здесь — начало сути запроса,
        // а не наша цитата, и в ленте этого текста больше нигде нет
        String body = reply.isBlank() || isForward(m.subject(), reply) ? MailText.normalize(m.body()) : reply;
        return new EmailLetter(key(m.messageId(), folder, uidValidity, m.uid()), m.subject().strip(), body,
                m.receivedAt(), m.fromAddress(), nameOf(m.from()), m.files());
    }

    /** Пересылка: тема «Fwd:» / «FW:» / «Пересл.:» или маркер пересланного письма ДО границы цитаты (в оставшемся тексте). */
    static boolean isForward(String subject, String reply) {
        return (subject != null && FORWARD_SUBJECT.matcher(subject).find()) || FORWARD_MARKER.matcher(reply).find();
    }

    static String key(String messageId, MailFolder folder, long uidValidity, long uid) {
        String id = messageId == null ? "" : messageId.strip();
        if (!id.replace("<", "").replace(">", "").isBlank()) {
            return id.length() <= KEY_MAX ? id : "sha256:" + sha256(id);
        }
        return "uid:" + folder.name().toLowerCase(Locale.ROOT) + ":" + uidValidity + ":" + uid;
    }

    /** «Иван Петров <ivan@x.kz>» → «Иван Петров»; голый адрес — null. */
    static String nameOf(String from) {
        if (from == null) return null;
        int lt = from.lastIndexOf('<');
        if (lt <= 0) return null;
        String name = from.substring(0, lt).strip().replaceAll("^\"|\"$", "").strip();
        return name.isEmpty() ? null : name;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
