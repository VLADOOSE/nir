package com.vladoose.nir.integration.telegram;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Настройки Bot API (`notify.telegram.*`): бот @West_Med_bot сайта westmed.kz, группа «Заявки», темы «Почта zakup@»
 * и «Тендеры» (новые профильные тендеры).
 * Токен наружу не отдаётся: только пакетный {@link #botToken()} для клиента, toString — без него.
 */
@Component
public class TelegramSettings {

    private static final Logger log = LoggerFactory.getLogger(TelegramSettings.class);

    private final boolean enabled;
    private final String apiUrl;
    private final String botToken;
    private final String chatId;
    private final String mailThreadId;
    private final String tendersThreadId;

    @Autowired
    public TelegramSettings(@Value("${notify.telegram.enabled:false}") boolean enabled,
                            @Value("${notify.telegram.api-url:https://api.telegram.org}") String apiUrl,
                            @Value("${notify.telegram.bot-token:}") String botToken,
                            @Value("${notify.telegram.chat-id:}") String chatId,
                            @Value("${notify.telegram.mail-thread-id:}") String mailThreadId,
                            @Value("${notify.telegram.tenders-thread-id:}") String tendersThreadId) {
        this.enabled = enabled;
        this.apiUrl = trim(apiUrl);
        this.botToken = trim(botToken);
        this.chatId = trim(chatId);
        this.mailThreadId = trim(mailThreadId);
        this.tendersThreadId = trim(tendersThreadId);
    }

    /** Без темы тендеров (почтовые тесты). */
    public TelegramSettings(boolean enabled, String apiUrl, String botToken, String chatId, String mailThreadId) {
        this(enabled, apiUrl, botToken, chatId, mailThreadId, "");
    }

    @PostConstruct
    void warnIfIncomplete() {
        if (enabled && !isConfigured()) {
            log.warn("Telegram включён (TELEGRAM_ENABLED=true), но не настроен: нужны TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT_ID — "
                    + "уведомления о письмах в очередь не ставятся");
        }
    }

    /** Включён и есть токен и чат — только тогда уведомления ставятся в очередь. */
    public boolean isConfigured() { return enabled && !botToken.isEmpty() && !chatId.isEmpty(); }

    public boolean enabled() { return enabled; }
    public String apiUrl() { return apiUrl; }
    public String chatId() { return chatId; }
    public String mailThreadId() { return mailThreadId; }
    public String tendersThreadId() { return tendersThreadId; }

    String botToken() { return botToken; }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    @Override
    public String toString() {
        return "TelegramSettings{enabled=" + enabled + ", chatId=" + chatId + ", mailThreadId=" + mailThreadId
                + ", tendersThreadId=" + tendersThreadId + ", botToken=" + (botToken.isEmpty() ? "—" : "***") + "}";
    }
}
