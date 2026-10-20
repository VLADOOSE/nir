package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vladoose.nir.integration.ndda.NddaHttpClient;
import com.vladoose.nir.integration.ndda.NddaRegistration;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Снимок РУ наших товаров с ndda.kz для гейта сверки (задача 14 плана 2026-10-20-product-spec-fit): № РУ — из фикстуры
 * каталога (site-catalog-full.json), проверка — тем же клиентом, что в работе ({@code NddaHttpClient.findRegistration},
 * карточка, комплектность). Не тест: запуск — REG_SNAPSHOT=1. Реестр публичный, без входа.
 */
@EnabledIfEnvironmentVariable(named = "REG_SNAPSHOT", matches = "1")
class RegistrationSnapshotTool {

    @Test
    void snapshot() throws Exception {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        Set<String> numbers = new TreeSet<>();
        try (InputStream in = getClass().getResourceAsStream("/product-watch/site-catalog-full.json")) {
            for (Map<String, Object> item : om.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {})) {
                String n = RegNumbers.normalize((String) item.get("registrationCert"));
                if (n != null) numbers.add(n);
            }
        }
        NddaHttpClient ndda = new NddaHttpClient(new ObjectMapper(), "https://oldregister.ndda.kz/register-backend");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String n : numbers) {
            Thread.sleep(300);
            NddaRegistration r = ndda.findRegistration(n);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("regNumber", n);
            if (r == null) {
                row.put("status", "NOT_FOUND");
                out.add(row);
                continue;
            }
            NddaDetailDto d = ndda.fetchDetail(r.id());
            List<NddaComplectItemDto> c = ndda.fetchComplectList(r.id());
            row.put("status", r.active() ? "ACTIVE" : "EXPIRED");
            row.put("expires", r.expirationDate());
            row.put("unlimited", r.unlimited());
            row.put("purpose", d == null ? null : d.getPurpose());
            row.put("useArea", d == null ? null : d.getUseArea());
            row.put("techChars", d == null ? null : d.getShortTechnicalCharacteristicsRu());
            row.put("components", c == null ? List.of() : c.stream()
                    .map(x -> x.getComponent() != null ? x.getComponent() : x.getProductName()).filter(Objects::nonNull).toList());
            out.add(row);
        }
        Files.writeString(Path.of("src/test/resources/product-watch/registrations.json"), om.writeValueAsString(out));
    }
}
