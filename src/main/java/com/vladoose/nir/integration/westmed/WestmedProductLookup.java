package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Бренд и slug товара по публичному поиску каталога. Кеш — на ОДИН цикл синхронизации
 * (новый экземпляр на цикл): одно имя — один запрос. Ошибка поиска не валит приём заявки.
 * На сайт уходит нормализованный текст в нижнем регистре — поиск там ILIKE, регистр не важен.
 */
public class WestmedProductLookup {

    private static final Logger log = LoggerFactory.getLogger(WestmedProductLookup.class);
    private static final int SEARCH_SIZE = 20;

    private final WestmedClient client;
    private final Map<String, List<WestmedProduct>> cache = new HashMap<>();

    public WestmedProductLookup(WestmedClient client) {
        this.client = client;
    }

    public Optional<WestmedProduct> byName(String name) {
        String n = norm(name);
        return search(name).stream().filter(p -> norm(p.name()).equals(n)).findFirst();
    }

    public Optional<WestmedProduct> bySlug(String slug, String nameHint) {
        if (slug == null || slug.isBlank()) return byName(nameHint);
        return search(nameHint).stream().filter(p -> slug.equals(p.slug())).findFirst();
    }

    private List<WestmedProduct> search(String text) {
        if (text == null || text.isBlank()) return List.of();
        return cache.computeIfAbsent(norm(text), key -> {
            try {
                return client.searchProducts(key, SEARCH_SIZE);
            } catch (RuntimeException e) {
                log.debug("westmed.kz: поиск товара «{}» не удался: {}", key, e.getMessage());
                return List.of();
            }
        });
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
