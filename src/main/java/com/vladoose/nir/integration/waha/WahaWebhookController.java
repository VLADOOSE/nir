package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.whatsapp.WhatsappChatSync;
import com.vladoose.nir.integration.whatsapp.WhatsappProviders;
import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Приём событий WAHA (спека whatsapp-waha §5.1): подпись → очередь → 200. Ни разбора, ни сети: у WAHA нет таймаута,
 * а медленный ответ задерживает её следующие события. Повторы WAHA живут только в её памяти — поэтому пишем сразу в
 * БД. Без входа в АИС (permitAll в SecurityConfig): зовёт только WAHA внутри сети docker; снаружи путь закрыт в nginx.
 */
@RestController
public class WahaWebhookController {

    public static final String PATH = "/api/whatsapp/waha/webhook";
    /** Настоящие события — килобайты (файлы WAHA сама не качает): больше — не читаем в память, 413. */
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(WahaWebhookController.class);

    private final WahaInboxWriter inbox;
    private final ObjectMapper objectMapper;
    private final WhatsappStatusHolder status;
    private final String hmacKey;
    private final boolean wahaSelected;
    private final String provider;
    private final AtomicBoolean warnedOtherProvider = new AtomicBoolean();

    public WahaWebhookController(WahaInboxWriter inbox, ObjectMapper objectMapper, WhatsappStatusHolder status,
                                 @Value("${chats.whatsapp.waha.hmac-key:}") String hmacKey,
                                 @Value("${chats.whatsapp.provider:waha}") String provider) {
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.status = status;
        this.hmacKey = hmacKey;
        this.provider = provider;
        this.wahaSelected = WhatsappProviders.WAHA.equals(WhatsappProviders.normalize(provider));
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Webhook-Hmac", required = false) String hmac,
                                        @RequestHeader(value = "X-Webhook-Request-Id", required = false) String requestId,
                                        HttpServletRequest request) throws IOException {
        byte[] body = readLimited(request);
        if (body == null) {
            log.warn("WAHA: вебхук отклонён — тело больше {} МБ", MAX_BODY_BYTES / (1024 * 1024));
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        return receive(hmac, requestId, body);
    }

    /** Тело не больше MAX_BODY_BYTES; больше (по заголовку или по факту) — null. */
    static byte[] readLimited(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) return null;
        try (InputStream in = request.getInputStream()) {
            byte[] b = in.readNBytes(MAX_BODY_BYTES + 1);
            return b.length > MAX_BODY_BYTES ? null : b;
        }
    }

    ResponseEntity<Void> receive(String hmac, String requestId, byte[] body) {
        if (!WahaSignature.verify(body, hmacKey, hmac)) {
            status.webhookRejected();
            log.warn("WAHA: вебхук отклонён — {} ({} байт)", hmac == null ? "нет подписи" : "подпись не сходится",
                    body == null ? 0 : body.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        status.webhookAccepted();
        if (!wahaSelected) {
            // откат на Green-API, а контейнер WAHA ещё работает: очередь росла бы без предела, а при возврате на WAHA
            // проиграла бы давние звонки и правки. 200 — чтобы WAHA не повторяла
            if (warnedOtherProvider.compareAndSet(false, true)) {
                log.warn("WAHA шлёт вебхуки, а провайдер WhatsApp — «{}»: события не записываются"
                        + " (остановите ais-waha и отвяжите устройство в телефоне)", provider);
            }
            return ResponseEntity.ok().build();
        }
        JsonNode env;
        try {
            env = objectMapper.readTree(body);
        } catch (IOException e) {
            env = null;
        }
        if (env == null || !env.isObject()) {
            // подписано нашим ключом, но не JSON: 4xx WAHA повторяла бы 12 раз впустую
            log.warn("WAHA: подписанный вебхук — не JSON-объект ({} байт), пропущен", body.length);
            return ResponseEntity.ok().build();
        }
        try {
            inbox.insert(env, requestId, new String(body, StandardCharsets.UTF_8));
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            if (WhatsappChatSync.isInfrastructureFailure(e)) {
                log.warn("WAHA: база недоступна — событие не записано, WAHA повторит ({})", e.getClass().getSimpleName());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            log.error("WAHA: событие не записано в очередь", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
