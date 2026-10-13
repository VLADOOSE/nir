package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Настройки ящика писем клиентов info@ (`mail.clients.*`, спека 2026-10-13-info-mail-leads §2.1). Ошибка настройки —
 * тот же ящик, что у приёма zakup@, неизвестный рынок, пустой адрес — выключает приём с ERROR при старте: иначе
 * опечатка молча превратила бы рынок в RF или повела бы два приёма по одному курсору.
 */
@Component
public class ClientMailSettings {

    private static final Logger log = LoggerFactory.getLogger(ClientMailSettings.class);
    /** Предел файла — не больше 100 МБ: файл письма читается в память целиком (куча прода — 1 ГБ). */
    static final int MAX_FILE_MB_LIMIT = 100;

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final Market market;
    private final long sinceDays;
    private final int maxFileBytes;
    private final ClientMailRules rules;

    public ClientMailSettings(@Value("${mail.clients.enabled:false}") boolean enabled,
                              @Value("${mail.clients.host:localhost}") String host,
                              @Value("${mail.clients.port:3143}") int port,
                              @Value("${mail.clients.username:info@westmed.kz}") String username,
                              @Value("${mail.clients.password:}") String password,
                              @Value("${mail.clients.protocol:imap}") String protocol,
                              @Value("${mail.clients.market:KZ}") String market,
                              @Value("${mail.clients.since-days:7}") long sinceDays,
                              @Value("${mail.clients.max-file-mb:25}") int maxFileMb,
                              @Value("${mail.clients.service-domains:*.mail.ru,2gis.ru,2gis.com,ecc.kz,goszakup.gov.kz}") String serviceDomains,
                              @Value("${leads.westmed.notification-from:info@westmed.kz}") String siteNotificationFrom,
                              @Value("${mail.imap.username:}") String zakupMailbox) {
        this.host = host;
        this.port = port;
        this.username = lower(username);
        this.password = password;
        this.protocol = protocol;
        this.market = parseMarket(market);
        this.sinceDays = Math.max(0, sinceDays);
        this.maxFileBytes = Math.max(1, Math.min(maxFileMb, MAX_FILE_MB_LIMIT)) * 1024 * 1024;
        this.rules = new ClientMailRules(lower(siteNotificationFrom), domainOf(this.username), domains(serviceDomains));
        String problem = !enabled ? null
                : !this.username.contains("@") ? "MAIL_CLIENTS_USERNAME не задан"
                : this.market == null ? "MAIL_CLIENTS_MARKET: неизвестный рынок «" + market + "» (нужно KZ или RF)"
                : this.username.equals(lower(zakupMailbox)) ? "MAIL_CLIENTS_USERNAME совпадает с ящиком приёма zakup@ (MAIL_IMAP_USERNAME)"
                : null;
        if (problem != null) log.error("Приём писем клиентов выключен: {}", problem);
        this.enabled = enabled && problem == null;
        if (this.enabled) {
            log.info("Приём писем клиентов: ящик {}, рынок {}, первый запуск папки — за {} дн.", this.username, this.market, this.sinceDays);
        }
    }

    public boolean enabled() { return enabled; }
    public String host() { return host; }
    public int port() { return port; }
    public String username() { return username; }
    public String password() { return password; }
    public String protocol() { return protocol; }
    public Market market() { return market; }
    public long sinceDays() { return sinceDays; }
    public int maxFileBytes() { return maxFileBytes; }
    public ClientMailRules rules() { return rules; }

    /** null — рынок не распознан (опечатка не превращается молча в RF, как у Market.fromHeader). */
    static Market parseMarket(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Market.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> domains(String raw) {
        return raw == null ? List.of() : Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 ? "" : address.substring(at + 1);
    }

    private static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
}
