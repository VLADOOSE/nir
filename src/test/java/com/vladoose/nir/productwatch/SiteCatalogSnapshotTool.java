package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vladoose.nir.integration.westmed.WestmedHttpClient;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Снимок каталога сайта с описаниями для калибровки сверки ТЗ (задача 6 плана, шаг 4; согласие оператора 2026-10-20).
 * Не тест: запуск — SITE_CATALOG_SNAPSHOT=1 WESTMED_USERNAME=… WESTMED_PASSWORD=… (только чтение админ-API).
 */
@EnabledIfEnvironmentVariable(named = "SITE_CATALOG_SNAPSHOT", matches = "1")
class SiteCatalogSnapshotTool {

    @Test
    void snapshot() throws Exception {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        WestmedHttpClient site = new WestmedHttpClient(json, "https://westmed.kz",
                System.getenv("WESTMED_USERNAME"), System.getenv("WESTMED_PASSWORD"));
        List<WestmedCatalogItem> items = site.adminCatalog();
        List<Map<String, Object>> out = new ArrayList<>();
        for (WestmedCatalogItem i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id().toString());
            m.put("name", i.name());
            m.put("slug", i.slug());
            m.put("brand", i.brand());
            m.put("category", i.category());
            m.put("shortDescription", i.shortDescription());
            m.put("description", i.description());
            m.put("attributes", i.attributes());
            m.put("registrationCert", i.registrationCert());
            out.add(m);
        }
        Files.writeString(Paths.get("src/test/resources/product-watch/site-catalog-full.json"),
                json.enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out));
        System.out.println("SNAPSHOT товаров: " + out.size());
    }
}
