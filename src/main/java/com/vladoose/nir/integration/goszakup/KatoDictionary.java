package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.KatoRefDto;
import com.vladoose.nir.integration.goszakup.dto.KatoRefPageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * КАТО-коды по региону для серверного фильтра v3 (фильтр принимает только точные 9-значные коды,
 * масок нет → шлём все коды области массивом). Справочник /v2/refs/ref_kato (~17k записей, 34 страницы)
 * статичен — грузится лениво один раз на процесс и кешируется в памяти.
 */
@Component
public class KatoDictionary {

    private static final Logger log = LoggerFactory.getLogger(KatoDictionary.class);

    /** Каноническое имя региона (== значениям фильтра на фронте и RegionResolver) → префикс ab КАТО. */
    private static final Map<String, String> REGION_TO_AB = Map.ofEntries(
            Map.entry("Абайская область", "10"),
            Map.entry("Акмолинская область", "11"),
            Map.entry("Актюбинская область", "15"),
            Map.entry("Алматинская область", "19"),
            Map.entry("Атырауская область", "23"),
            Map.entry("Западно-Казахстанская область", "27"),
            Map.entry("Жамбылская область", "31"),
            Map.entry("Жетысуская область", "33"),
            Map.entry("Карагандинская область", "35"),
            Map.entry("Костанайская область", "39"),
            Map.entry("Кызылординская область", "43"),
            Map.entry("Мангистауская область", "47"),
            Map.entry("Павлодарская область", "55"),
            Map.entry("Северо-Казахстанская область", "59"),
            Map.entry("Туркестанская область", "61"),
            Map.entry("Улытауская область", "62"),
            Map.entry("Восточно-Казахстанская область", "63"),
            Map.entry("г. Астана", "71"),
            Map.entry("г. Алматы", "75"),
            Map.entry("г. Шымкент", "79"));

    private final GoszakupClient client;
    private volatile Map<String, List<String>> codesByAb;

    public KatoDictionary(GoszakupClient client) {
        this.client = client;
    }

    /** Все КАТО-коды области; пустой список, если регион не распознан. */
    public List<String> codesForRegion(String regionName) {
        String ab = REGION_TO_AB.get(regionName == null ? "" : regionName.trim());
        if (ab == null) return List.of();
        return byAb().getOrDefault(ab, List.of());
    }

    private synchronized Map<String, List<String>> byAb() {
        if (codesByAb != null) return codesByAb;
        Map<String, List<String>> m = new HashMap<>();
        String cursor = null;
        int pages = 0;
        do {
            KatoRefPageDto page = client.fetchKatoPage(cursor);
            List<KatoRefDto> items = page.getItems() != null ? page.getItems() : List.of();
            for (KatoRefDto k : items) {
                if (k.getAb() != null && !k.getAb().isBlank()) {
                    m.computeIfAbsent(k.getAb(), x -> new ArrayList<>()).add(k.code());
                }
            }
            cursor = page.getNextPage();
            pages++;
        } while (cursor != null && !cursor.isBlank() && pages < 100); // ~34 страницы; 100 — предохранитель
        log.info("goszakup: справочник КАТО загружен: {} страниц, {} регионов", pages, m.size());
        codesByAb = m;
        return m;
    }
}
