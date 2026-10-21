package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.GatewayHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Кнопки бота (спека 2026-10-21-tender-telegram-bot §7–8): сообщения с HTML и кнопками в тему «нашей продукции», ответы на
 * нажатия, правка кнопок и удаление своих сообщений, файл и фото (multipart), приём обновлений getUpdates. Уведомления без
 * кнопок шлёт по-прежнему {@link TelegramClient}. Токен стоит в пути — ни в одном тексте ошибки.
 */
@Component
public class TelegramBotApi {

    static final Duration DEADLINE = Duration.ofSeconds(30);
    /** long-poll getUpdates: Telegram держит запрос до 25 с, дедлайн всего обмена — на 10 с больше. */
    public static final int POLL_SECONDS = 25;
    /** Предел текста кнопки «скопировать» (Bot API CopyTextButton). */
    public static final int COPY_MAX = 256;
    /** 429 с ожиданием не дольше этого — повтор, дольше — ошибка действию. */
    static final int RETRY_MAX_SECONDS = 15;

    /** Кнопка: обратный вызов (data, до 64 байт) или ссылка. */
    public record Button(String text, String data, String url, String copy) {
        public static Button cb(String text, String data) { return new Button(text, data, null, null); }
        public static Button link(String text, String url) { return new Button(text, null, url, null); }
        /** «Скопировать» (Bot API copy_text): текст до 256 знаков попадает в буфер обмена телефона. */
        public static Button copy(String text, String value) { return new Button(text, null, null, value); }
    }

    private final TelegramSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile Long botId;

    @Autowired
    public TelegramBotApi(TelegramSettings settings, ObjectMapper json) {
        this(settings, json, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    TelegramBotApi(TelegramSettings settings, ObjectMapper json, HttpClient http) {
        this.settings = settings;
        this.json = json;
        this.http = http;
    }

    /** Чат группы «Заявки» (TELEGRAM_CHAT_ID); не число — 0, тогда бот никого не слушает. */
    public long chatId() {
        try {
            return Long.parseLong(settings.chatId());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public long sendMessage(String html, boolean silent, List<List<Button>> keyboard, Long replyTo) {
        Map<String, Object> b = base(replyTo);
        b.put("text", html);
        b.put("parse_mode", "HTML");
        b.put("link_preview_options", Map.of("is_disabled", true));
        if (silent) b.put("disable_notification", true);
        if (keyboard != null && !keyboard.isEmpty()) b.put("reply_markup", Map.of("inline_keyboard", markup(keyboard)));
        return call("sendMessage", b, DEADLINE).path("message_id").asLong(0);
    }

    /** Просьба ответить (ForceReply): Telegram сам открывает поле ответа на это сообщение. */
    public long sendForceReply(String html, Long replyTo, String placeholder) {
        Map<String, Object> b = base(replyTo);
        b.put("text", html);
        b.put("parse_mode", "HTML");
        b.put("disable_notification", true);
        Map<String, Object> fr = new LinkedHashMap<>();
        fr.put("force_reply", true);
        if (placeholder != null) fr.put("input_field_placeholder", TelegramText.safeCut(placeholder, 64));
        b.put("reply_markup", fr);
        return call("sendMessage", b, DEADLINE).path("message_id").asLong(0);
    }

    public void editKeyboard(long messageId, List<List<Button>> keyboard) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("chat_id", settings.chatId());
        b.put("message_id", messageId);
        b.put("reply_markup", Map.of("inline_keyboard", markup(keyboard == null ? List.of() : keyboard)));
        ignore400(() -> call("editMessageReplyMarkup", b, DEADLINE));   // «message is not modified» / удалено — не ошибка
    }

    public void deleteMessage(long messageId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("chat_id", settings.chatId());
        b.put("message_id", messageId);
        ignore400(() -> call("deleteMessage", b, DEADLINE));   // старше 48 ч или уже удалено — остаётся как есть
    }

    public void answerCallback(String callbackId, String text, boolean alert) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("callback_query_id", callbackId);
        if (text != null && !text.isEmpty()) b.put("text", TelegramText.safeCut(text, 200));
        if (alert) b.put("show_alert", true);
        ignore400(() -> call("answerCallbackQuery", b, DEADLINE));   // нажатие устарело — не ошибка
    }

    public long sendDocument(String fileName, byte[] bytes, String contentType, String captionHtml,
                             List<List<Button>> keyboard, Long replyTo) {
        return upload("sendDocument", "document", fileName, bytes, contentType, captionHtml, keyboard, replyTo);
    }

    public long sendPhoto(String fileName, byte[] png, String captionHtml, List<List<Button>> keyboard, Long replyTo) {
        return upload("sendPhoto", "photo", fileName, png, "image/png", captionHtml, keyboard, replyTo);
    }

    public List<BotUpdate> getUpdates(long offset, int timeoutSeconds) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("offset", offset);
        b.put("timeout", timeoutSeconds);
        b.put("allowed_updates", List.of("message", "callback_query"));
        JsonNode r = call("getUpdates", b, Duration.ofSeconds(timeoutSeconds + 10L));
        List<BotUpdate> out = new ArrayList<>();
        if (r.isArray()) r.forEach(u -> out.add(BotUpdate.of(u)));
        return out;
    }

    /** id самого бота — ответ «на сообщение бота» узнаётся по нему; один запрос getMe на запуск. */
    public long botId() {
        Long id = botId;
        if (id == null) {
            id = call("getMe", Map.of(), DEADLINE).path("id").asLong(0);
            botId = id;
        }
        return id;
    }

    private String thread() {
        return settings.productsThreadId().isEmpty() ? settings.tendersThreadId() : settings.productsThreadId();
    }

    private Map<String, Object> base(Long replyTo) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("chat_id", settings.chatId());
        String t = thread();
        if (!t.isEmpty()) b.put("message_thread_id", threadId(t));
        if (replyTo != null) b.put("reply_parameters", Map.of("message_id", replyTo, "allow_sending_without_reply", true));
        return b;
    }

    private static long threadId(String t) {
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new TelegramException(0, "Telegram: тема «наших товаров» должна быть числом (id темы группы)", null);
        }
    }

    private static List<List<Map<String, Object>>> markup(List<List<Button>> keyboard) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (List<Button> row : keyboard) {
            List<Map<String, Object>> r = new ArrayList<>();
            for (Button btn : row) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("text", btn.text());
                if (btn.url() != null) m.put("url", btn.url());
                else if (btn.copy() != null) m.put("copy_text", Map.of("text", TelegramText.safeCut(btn.copy(), COPY_MAX)));
                else m.put("callback_data", btn.data());
                r.add(m);
            }
            if (!r.isEmpty()) rows.add(r);
        }
        return rows;
    }

    private interface Call { void run(); }

    private static void ignore400(Call c) {
        try {
            c.run();
        } catch (TelegramException e) {
            if (e.status() != 400) throw e;
        }
    }

    private long upload(String method, String field, String fileName, byte[] bytes, String contentType, String captionHtml,
                        List<List<Button>> keyboard, Long replyTo) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chat_id", settings.chatId());
        String t = thread();
        if (!t.isEmpty()) fields.put("message_thread_id", String.valueOf(threadId(t)));
        if (captionHtml != null && !captionHtml.isEmpty()) {
            fields.put("caption", captionHtml);
            fields.put("parse_mode", "HTML");
        }
        fields.put("disable_notification", "true");
        try {
            if (replyTo != null) fields.put("reply_parameters", json.writeValueAsString(Map.of("message_id", replyTo,
                    "allow_sending_without_reply", true)));
            if (keyboard != null && !keyboard.isEmpty()) {
                fields.put("reply_markup", json.writeValueAsString(Map.of("inline_keyboard", markup(keyboard))));
            }
        } catch (JsonProcessingException e) {
            throw new TelegramException(0, "Telegram: кнопки не собрались", null);
        }
        String boundary = "ais" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, fields, field, fileName, contentType, bytes);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(uri(method)).timeout(DEADLINE)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        } catch (IllegalArgumentException e) {
            throw new TelegramException(0, "Telegram: адрес API или токен в настройках некорректны", null);
        }
        return send(req, DEADLINE, method).path("message_id").asLong(0);
    }

    private static byte[] multipart(String boundary, Map<String, String> fields, String fileField, String fileName,
                                    String contentType, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n");
            write(out, e.getValue());
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                + fileName.replaceAll("[\"\\\\\\r\\n]", "_") + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(data);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode call(String method, Map<String, Object> body, Duration deadline) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(uri(method)).timeout(deadline).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException | JsonProcessingException e) {   // текст IAE содержит адрес — наружу не отдаём
            throw new TelegramException(0, "Telegram: адрес API или токен в настройках некорректны", null);
        }
        return send(req, deadline, method);
    }

    /**
     * Обмен и разбор ответа. 429 (предел сообщений группы, общий с сайтом и medsite) с коротким retry_after — один повтор
     * (спека §8); длинное ожидание — наверх: поток бота один, минута ожидания — минута без нажатий. getUpdates не повторяем —
     * паузу держит цикл приёма.
     */
    private JsonNode send(HttpRequest req, Duration deadline, String method) {
        try {
            return result(exchange(req, deadline, method));
        } catch (TelegramException e) {
            Integer wait = e.retryAfterSeconds();
            if (e.status() != 429 || wait == null || wait > RETRY_MAX_SECONDS || "getUpdates".equals(method)) throw e;
            try {
                Thread.sleep(wait * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return result(exchange(req, deadline, method));
        }
    }

    private URI uri(String method) {
        return URI.create(settings.apiUrl().replaceAll("/+$", "") + "/bot" + settings.botToken() + "/" + method);
    }

    private HttpResponse<String> exchange(HttpRequest req, Duration deadline, String method) {
        try {
            return GatewayHttp.exchange(http, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                    deadline, "Telegram", "методе " + method);
        } catch (GatewayException e) {
            throw new TelegramException(0, e.getMessage(), null);
        }
    }

    private JsonNode result(HttpResponse<String> resp) {
        JsonNode node;
        try {
            node = resp.body() == null || resp.body().isBlank() ? null : json.readTree(resp.body());
        } catch (JsonProcessingException e) {
            node = null;
        }
        if (resp.statusCode() == 200 && node != null && node.path("ok").asBoolean(false)) return node.path("result");
        Integer retryAfter = node != null && node.path("parameters").has("retry_after")
                ? node.path("parameters").path("retry_after").asInt() : null;
        String desc = node == null ? "" : TelegramClient.maskToken(node.path("description").asText(""), settings.botToken());
        throw new TelegramException(resp.statusCode(), "Telegram: HTTP " + resp.statusCode()
                + (desc.isBlank() ? "" : " — " + TelegramText.safeCut(desc, 200)), retryAfter);
    }
}
