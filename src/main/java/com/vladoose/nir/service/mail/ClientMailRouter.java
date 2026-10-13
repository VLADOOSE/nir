package com.vladoose.nir.service.mail;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Куда идёт письмо «Входящих» ящика info@ — чистая функция, порядок правил значим (спека 2026-10-13-info-mail-leads
 * §2.2): уведомление сайта → возврат / задержка / автоответ / квитанция → рассылка → служебное → свой домен →
 * переписка. Те же «служебное» и «свой домен» отсеивают адресатов наших писем из «Отправленных».
 */
public final class ClientMailRouter {

    /** Служебные имена адреса. mailer-daemon и postmaster в письме раньше ловит правило возврата. */
    static final Set<String> SERVICE_LOCAL_PARTS =
            Set.of("noreply", "no-reply", "not-reply", "donotreply", "do-not-reply", "mailer-daemon", "postmaster");
    private static final Set<String> BULK = Set.of("bulk", "list", "junk");

    private ClientMailRouter() {}

    public static ClientMailRoute route(ParsedMail m, ClientMailRules r) {
        if (MailClassifier.isSiteNotification(m, r.siteNotificationFrom())) return ClientMailRoute.SITE_NOTIFICATION;
        if (MailClassifier.isBounce(m)) return MailClassifier.isDelayed(m) ? ClientMailRoute.DELAYED : ClientMailRoute.BOUNCE;
        if (MailClassifier.isAutoReply(m)) return ClientMailRoute.AUTO_REPLY;
        if (isNewsletter(m)) return ClientMailRoute.NEWSLETTER;
        if (isService(m.fromAddress(), r)) return ClientMailRoute.SERVICE;
        if (isOwnDomain(m.fromAddress(), r)) return ClientMailRoute.OWN_DOMAIN;
        return ClientMailRoute.CONVERSATION;
    }

    /** Рассылка: List-Unsubscribe / List-Id (RFC 2369 / 2919) или Precedence: bulk | list | junk. */
    static boolean isNewsletter(ParsedMail m) {
        Map<String, String> h = m.autoHeaders();
        return h.containsKey("list-unsubscribe") || h.containsKey("list-id")
                || BULK.contains(h.getOrDefault("precedence", ""));
    }

    /** Служебный адрес: служебное имя или домен из списка. Адрес без «@» — тоже: переписку вести не с кем. */
    public static boolean isService(String address, ClientMailRules r) {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        int at = a.lastIndexOf('@');
        if (at <= 0 || at == a.length() - 1) return true;
        if (SERVICE_LOCAL_PARTS.contains(a.substring(0, at))) return true;
        String domain = a.substring(at + 1);
        return r.serviceDomains().stream().anyMatch(p -> domainMatches(domain, p));
    }

    /** Свой домен (домен ящика) и его поддомены. */
    public static boolean isOwnDomain(String address, ClientMailRules r) {
        if (address == null || r.ownDomain().isBlank()) return false;
        String a = address.trim().toLowerCase(Locale.ROOT);
        int at = a.lastIndexOf('@');
        return at >= 0 && domainMatches(a.substring(at + 1), r.ownDomain());
    }

    /** «2gis.ru» — сам домен и поддомены; «*.mail.ru» — только поддомены. */
    static boolean domainMatches(String domain, String pattern) {
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("*.")) return domain.endsWith(p.substring(1));
        return domain.equals(p) || domain.endsWith("." + p);
    }
}
