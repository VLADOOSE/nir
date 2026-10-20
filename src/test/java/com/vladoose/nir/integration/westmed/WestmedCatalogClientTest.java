package com.vladoose.nir.integration.westmed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Каталог сайта для поиска нашей продукции (спека 2026-10-19-product-watch §6): админ-API и публичный список. */
class WestmedCatalogClientTest {

    HttpServer server;
    final AtomicInteger publicCards = new AtomicInteger();
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    static final String A = "11111111-1111-1111-1111-111111111111";
    static final String B = "22222222-2222-2222-2222-222222222222";
    static final String C = "33333333-3333-3333-3333-333333333333";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", ex -> reply(ex, 200, "{\"accessToken\":\"t\"}"));
        server.createContext("/api/admin/products", ex -> {
            String path = ex.getRequestURI().getPath();
            String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            if (path.equals("/api/admin/products")) {
                if (q.contains("page=0")) reply(ex, 200, page(false, item(A, "Блок аспирации для аппарата АСД-\\\"ФОТЕК\\\""), item(B, "Электрокардиограф ЭК12Т-01")));
                else reply(ex, 200, page(true, item(C, "Удалённый товар", "\"deletedAt\":\"2026-10-01T00:00:00Z\"")));
                return;
            }
            String id = path.substring("/api/admin/products/".length());
            reply(ex, 200, "{\"id\":\"" + id + "\",\"name\":\"Карточка " + id.substring(0, 1) + "\",\"slug\":\"s-" + id.substring(0, 1)
                    + "\",\"sku\":\"sku\",\"brandName\":\"Фотек\",\"categoryName\":\"Хирургическое оборудование\","
                    + "\"description\":\"Комплект: блок аспирации, пневмопедаль, держатель шланга аспирационного напольный\","
                    + "\"shortDescription\":\"кратко\",\"registrationCert\":\"РК-МТ-7№010807\",\"updatedAt\":\"2026-10-10T05:00:00Z\","
                    + "\"attributes\":[{\"attributeName\":\"Производительность\",\"attributeValue\":\"300 л/мин\",\"sortOrder\":1}]}");
        });
        server.createContext("/api/v1/products", ex -> {
            if (!ex.getRequestURI().getPath().equals("/api/v1/products")) { publicCards.incrementAndGet(); reply(ex, 404, "{}"); return; }
            reply(ex, 200, "{\"content\":[{\"id\":\"" + A + "\",\"name\":\"Блок аспирации\",\"slug\":\"blok\",\"sku\":\"fotek-1\","
                    + "\"shortDescription\":\"кратко\",\"brandName\":\"Фотек\",\"categoryName\":\"Хирургическое оборудование\"}],"
                    + "\"last\":true,\"totalPages\":1,\"number\":0}");
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private static String item(String id, String name, String... extra) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"slug\":\"x\",\"sku\":\"s\",\"categoryName\":\"Кат\","
                + "\"brandName\":\"Фотек\",\"status\":\"ACTIVE\"" + (extra.length > 0 ? "," + extra[0] : "") + "}";
    }

    private static String page(boolean last, String... items) {
        return "{\"content\":[" + String.join(",", items) + "],\"last\":" + last + ",\"number\":" + (last ? 1 : 0) + "}";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private WestmedHttpClient client(String user, String pass) {
        return new WestmedHttpClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), user, pass, Duration.ofSeconds(5));
    }

    @Test
    void adminCatalog_allPages_cardsWithDescriptionAttributes_skipsDeleted_neverPublicCard() {
        List<WestmedCatalogItem> items = client("ais@westmed.kz", "secret").adminCatalog();
        assertThat(items).extracting(WestmedCatalogItem::id).extracting(Object::toString).containsExactly(A, B);
        WestmedCatalogItem a = items.get(0);
        assertThat(a.description()).contains("держатель шланга аспирационного напольный");
        assertThat(a.attributes()).extracting(x -> x.name() + "=" + x.value()).containsExactly("Производительность=300 л/мин");
        assertThat(a.registrationCert()).isEqualTo("РК-МТ-7№010807");
        assertThat(a.updatedAt()).isNotNull();
        assertThat(publicCards.get()).isZero();
    }

    @Test
    void publicCatalog_listOnly() {
        WestmedHttpClient c = client("", "");
        assertThat(c.adminConfigured()).isFalse();
        List<WestmedCatalogItem> items = c.publicCatalog();
        assertThat(items).extracting(WestmedCatalogItem::name).containsExactly("Блок аспирации");
        assertThat(items.get(0).description()).isNull();
        assertThat(publicCards.get()).isZero();
    }
}
