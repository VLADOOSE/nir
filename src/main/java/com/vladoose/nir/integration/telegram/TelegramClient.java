package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.GatewayHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bot API: sendMessage в тему почты или тендеров. Обмен — с дедлайном на ВЕСЬ ответ (GatewayHttp: таймаут HttpRequest в JDK 17
 * снимается после заголовков). Адрес запроса содержит токен — он не попадает ни в одно сообщение об ошибке.
 */
@Component
public class TelegramClient {

    static final Duration DEADLINE = Duration.ofSeconds(15);

    private final TelegramSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration deadline;

    @Autowired
    public TelegramClient(TelegramSettings settings, ObjectMapper json) {
        this(settings, json, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build(), DEADLINE);
    }

    TelegramClient(TelegramSettings settings, ObjectMapper json, HttpClient http, Duration deadline) {
        this.settings = settings;
        this.json = json;
        this.http = http;
        this.deadline = deadline;
    }

    /** Сообщение в тему почты (`TELEGRAM_MAIL_THREAD_ID`; пусто — «Общая»). Возвращает message_id. */
    public long sendMail(String text, boolean silent) {
        return send(settings.mailThreadId(), "TELEGRAM_MAIL_THREAD_ID", text, silent);
    }

    /** Сообщение о новых тендерах в их тему (`TELEGRAM_TENDERS_THREAD_ID`; пусто — «Общая»), тихо (просьба оператора). */
    public long sendTenders(String text) {
        return send(settings.tendersThreadId(), "TELEGRAM_TENDERS_THREAD_ID", text, true);
    }

    /** threadVariable — имя переменной окружения темы: в тексте ошибки, если там не число. */
    private long send(String thread, String threadVariable, String text, boolean silent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", settings.chatId());
        if (!thread.isEmpty()) {
            try {
                body.put("message_thread_id", Long.parseLong(thread));
            } catch (NumberFormatException e) {
                throw new TelegramException(0, "Telegram: " + threadVariable + " должен быть числом (id темы группы)", null);
            }
        }
        body.put("text", text);
        if (silent) body.put("disable_notification", true);
        body.put("link_preview_options", Map.of("is_disabled", true));

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(settings.apiUrl().replaceAll("/+$", "")
                            + "/bot" + settings.botToken() + "/sendMessage"))
                    .timeout(deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException | JsonProcessingException e) {   // текст IAE содержит адрес — наружу не отдаём
            throw new TelegramException(0, "Telegram: адрес API или токен в настройках некорректны", null);
        }

        HttpResponse<String> resp;
        try {
            resp = GatewayHttp.exchange(http, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                    deadline, "Telegram", "отправке сообщения");
        } catch (GatewayException e) {
            throw new TelegramException(0, e.getMessage(), null);
        }

        JsonNode node = parse(resp.body());
        if (resp.statusCode() == 200 && node != null && node.path("ok").asBoolean(false)) {
            return node.path("result").path("message_id").asLong(0);
        }
        Integer retryAfter = node != null && node.path("parameters").has("retry_after")
                ? node.path("parameters").path("retry_after").asInt() : null;
        // текст сервера: прокси или страница ошибки могут повторить путь запроса вместе с токеном — вычистить ДО обрезки
        String desc = node == null ? "" : maskToken(node.path("description").asText(""), settings.botToken());
        throw new TelegramException(resp.statusCode(), "Telegram: HTTP " + resp.statusCode()
                + (desc.isBlank() ? "" : " — " + TelegramText.safeCut(desc, 200)), retryAfter);
    }

    /**
     * Токен заменяется на «***» целиком и отдельно его секретная часть после «:» — её символы в адресе не экранируются,
     * так что она уцелела бы и там, где двоеточие пришло как %3A.
     */
    private static String maskToken(String text, String token) {
        if (token.isEmpty()) return text;
        String out = text.replace(token, "***");
        String secret = token.substring(token.indexOf(':') + 1);
        return secret.isEmpty() ? out : out.replace(secret, "***");
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : json.readTree(body);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
