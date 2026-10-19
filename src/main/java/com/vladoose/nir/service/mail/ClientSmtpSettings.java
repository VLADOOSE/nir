package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Отправка писем клиентам с ящика info@ (спека 2026-10-19-client-mail-reply §3.1): свой SMTP, вход — тот же, что у приёма
 * (mail.clients.username / password), рынок — mail.clients.market. Пустой хост — отправка выключена; от включения приёма
 * (mail.clients.enabled) не зависит. Почта поставщикам (spring.mail.*, zakup@) не меняется.
 */
@Component
public class ClientSmtpSettings {

    private static final Logger log = LoggerFactory.getLogger(ClientSmtpSettings.class);
    public static final String NOT_CONFIGURED = "Отправка писем клиентам не настроена";

    private final String host;
    private final int port;
    private final boolean ssl;
    private final String username;
    private final String password;
    private final Market market;
    private final String fromName;
    private final boolean sentCopy;

    public ClientSmtpSettings(@Value("${mail.clients.smtp.host:}") String host,
                              @Value("${mail.clients.smtp.port:465}") int port,
                              @Value("${mail.clients.smtp.ssl:true}") boolean ssl,
                              @Value("${mail.clients.username:info@westmed.kz}") String username,
                              @Value("${mail.clients.password:}") String password,
                              @Value("${mail.clients.market:KZ}") String market,
                              @Value("${mail.clients.from-name:West-Med}") String fromName,
                              @Value("${mail.clients.sent-copy.enabled:true}") boolean sentCopy) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.ssl = ssl;
        this.username = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.market = ClientMailSettings.parseMarket(market);
        this.fromName = fromName == null || fromName.isBlank() ? "West-Med" : fromName.trim();
        this.sentCopy = sentCopy;
        if (enabled()) log.info("Отправка писем клиентам: ящик {}, рынок {}", this.username, this.market);
    }

    /** Всё нужное для входа на SMTP задано. */
    public boolean enabled() {
        return !host.isEmpty() && username.contains("@") && !password.isBlank() && market != null;
    }

    /** null — с этого рынка можно отправлять; иначе — причина для окна письма и страницы шаблонов. */
    public String problem(Market current) {
        if (!enabled()) return NOT_CONFIGURED;
        if (current != market) return "Письма клиентам — только на рынке " + market + " (ящик " + username + ")";
        return null;
    }

    public String host() { return host; }
    public int port() { return port; }
    public boolean ssl() { return ssl; }
    public String username() { return username; }
    public String password() { return password; }
    public Market market() { return market; }
    public String fromName() { return fromName; }
    public boolean sentCopy() { return sentCopy; }
}
