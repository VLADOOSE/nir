package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.whatsapp.WhatsappChatSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Приём событий WAHA (спека whatsapp-waha §5.1): подпись → очередь → 200. Ни разбора, ни сети: у WAHA нет таймаута,
 * а медленный ответ задерживает её следующие события. Повторы WAHA живут только в её памяти — поэтому пишем сразу в
 * БД. Без входа в АИС (permitAll в SecurityConfig): зовёт только WAHA внутри сети docker; снаружи путь закрыт в nginx.
 */
@RestController
public class WahaWebhookController {

    public static final String PATH = "/api/whatsapp/waha/webhook";
    private static final Logger log = LoggerFactory.getLogger(WahaWebhookController.class);

    private final WahaInboxWriter inbox;
    private final ObjectMapper objectMapper;
    private final String hmacKey;

    public WahaWebhookController(WahaInboxWriter inbox, ObjectMapper objectMapper,
                                 @Value("${chats.whatsapp.waha.hmac-key:}") String hmacKey) {
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.hmacKey = hmacKey;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Webhook-Hmac", required = false) String hmac,
                                        @RequestHeader(value = "X-Webhook-Request-Id", required = false) String requestId,
                                        @RequestBody(required = false) byte[] body) {
        if (!WahaSignature.verify(body, hmacKey, hmac)) {
            log.warn("WAHA: вебхук отклонён — {} ({} байт)", hmac == null ? "нет подписи" : "подпись не сходится",
                    body == null ? 0 : body.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
