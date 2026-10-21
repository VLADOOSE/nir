package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.repository.ProductDigestRepository;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Сообщения бота, на которые ждём блок «Для АИС» (спека §8): просьбы «✍️ Ответ ИИ», файлы «Для ИИ», списки писем — в памяти
 * (до 500 последних; после перезапуска — только сообщения-пачки из БД). Ответ без блока на них — подсказка, на прочие — молча.
 */
@Component
public class BotPrompts {

    private static final int MAX = 500;
    private final ProductDigestRepository digests;
    private final Set<Long> ids = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > MAX; }
    }));

    public BotPrompts(ProductDigestRepository digests) { this.digests = digests; }

    public void add(long messageId) { ids.add(messageId); }

    public boolean contains(Long messageId) {
        return messageId != null && (ids.contains(messageId) || digests.existsByMessageId(messageId));
    }
}
