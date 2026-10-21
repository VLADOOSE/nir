package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.ProductDigest;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.repository.ProductDigestRepository;
import com.vladoose.nir.service.productwatch.ProductDigestComposer;
import com.vladoose.nir.service.productwatch.ProductWatchNotifier;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Отправка сообщения-пачки с кнопками (спека 2026-10-21-tender-telegram-bot §4): пачка сохраняется до отправки — её id
 * в кнопках; не ушло — пачка удаляется, тендеры не отмечаются сообщёнными (повтор — следующим прогоном).
 */
@Component
public class ProductDigestPublisher implements ProductWatchNotifier.DigestPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductDigestPublisher.class);

    private final ProductDigestRepository digests;
    private final TelegramBotApi api;
    private final String publicUrl;

    public ProductDigestPublisher(ProductDigestRepository digests, TelegramBotApi api, @Value("${ais.public-url:}") String publicUrl) {
        this.digests = digests;
        this.api = api;
        this.publicUrl = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
    }

    @Override
    public boolean publish(ProductDigestComposer.Chunk chunk) {
        ProductDigest d = digests.save(ProductDigest.builder().tenderIds(new ArrayList<>(chunk.tenderIds())).build());
        try {
            long mid = api.sendMessage(chunk.html(), !chunk.sound(), keyboard(d.getId(), chunk.tenderIds(), publicUrl), null);
            d.setMessageId(mid);
            digests.save(d);
            return true;
        } catch (RuntimeException e) {
            log.warn("Telegram: сообщение-пачка ({} тендеров) не ушло: {}", chunk.tenderIds().size(), ErrorText.of(e));
            digests.deleteById(d.getId());
            return false;
        }
    }

    public static List<List<Button>> keyboard(long digestId, List<Long> tenderIds, String base) {
        List<List<Button>> kb = new ArrayList<>();
        kb.add(List.of(Button.cb("📋 Для ИИ", "d:" + digestId + ":ai"), Button.cb("✍️ Ответ ИИ", "d:" + digestId + ":ans")));
        if (base != null) {
            kb.add(List.of(Button.link("Открыть в АИС", base + "/tenders?ids="
                    + tenderIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "&market=KZ")));
        }
        return kb;
    }
}
