package com.vladoose.nir.service.mail;

import com.vladoose.nir.util.KpToken;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Вид письма (спека §4.1) — чистая функция, порядок проверок значим. */
public final class MailClassifier {

    private static final Set<String> DAEMONS = Set.of("mailer-daemon", "postmaster", "mail-daemon");
    private static final Pattern AUTO_SUBJECT = Pattern.compile(
            "^\\s*(автоответ|автоматический ответ|auto:|automatic reply|autoreply|auto-reply|out of office)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private MailClassifier() {}

    public static Classification classify(ParsedMail m, ClassifierRules r) {
        String from = m.fromAddress();
        if (!r.siteNotificationFrom().isBlank() && from.equalsIgnoreCase(r.siteNotificationFrom())
                && m.subject().strip().endsWith("— westmed.kz")) {
            return new Classification(MailClass.SITE_NOTIFICATION, null);
        }
        if (!r.ownAddress().isBlank() && from.equalsIgnoreCase(r.ownAddress())) {
            return new Classification(MailClass.OWN, null);
        }
        if (isBounce(m)) return new Classification(isDelayed(m) ? MailClass.DELAYED : MailClass.BOUNCE, bounceToken(m));
        Long token = KpToken.parse(m.subject()).orElse(null);
        if (isAutoReply(m)) return new Classification(MailClass.AUTO_REPLY, token);
        if (token != null) return new Classification(MailClass.SUPPLIER_RESPONSE, token);
        if (m.excelBytes() != null && r.clientRequests()) return new Classification(MailClass.CLIENT_REQUEST, null);
        return new Classification(MailClass.UNMATCHED, null);
    }

    /** Возврат: отчёт о доставке (multipart/report … delivery-status) или письмо почтового робота. */
    static boolean isBounce(ParsedMail m) {
        String ct = m.contentType();
        if (ct.startsWith("multipart/report") && ct.contains("delivery-status")) return true;
        String addr = m.fromAddress();
        int at = addr.indexOf('@');
        return DAEMONS.contains(at > 0 ? addr.substring(0, at) : addr);
    }

    /**
     * Отложенная доставка: отчёт о доставке с Action: delayed (RFC 3464) — сервер получателя ещё повторяет попытки.
     * Только по полю Action, не по коду статуса: итоговый отказ серверы шлют и с последним временным кодом (Postfix
     * после срока очереди — Action: failed и Status: 4.4.1, Exchange — 4.4.7 QUEUE.Expired), и такой отказ — «не
     * доставлено». Один признак и на вид письма, и на текст уведомления ({@link MailNotificationComposer}).
     */
    static boolean isDelayed(ParsedMail m) {
        return m.bounce() != null && "delayed".equals(m.bounce().action());
    }

    /** Метка запроса КП в возврате: тема исходного письма → тема возврата → текст возврата. */
    static Long bounceToken(ParsedMail m) {
        if (m.bounce() != null && m.bounce().originalSubject() != null) {
            Optional<Long> t = KpToken.parse(m.bounce().originalSubject());
            if (t.isPresent()) return t.get();
        }
        Optional<Long> t = KpToken.parse(m.subject());
        return t.isPresent() ? t.get() : KpToken.parse(m.body()).orElse(null);
    }

    /** Автоответ: RFC 3834 Auto-Submitted (кроме «no»), X-Autoreply / X-Autorespond, Precedence auto_reply, тема. */
    static boolean isAutoReply(ParsedMail m) {
        String as = m.autoHeaders().get("auto-submitted");
        if (as != null && !as.isBlank() && !as.startsWith("no")) return true;
        if (m.autoHeaders().containsKey("x-autoreply") || m.autoHeaders().containsKey("x-autorespond")) return true;
        if ("auto_reply".equals(m.autoHeaders().get("precedence"))) return true;
        return AUTO_SUBJECT.matcher(m.subject()).find();
    }
}
