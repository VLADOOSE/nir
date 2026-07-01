package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Живые формы ответов goszakup, снятые реальным токеном (стаб на JDK HttpServer, без сети). */
class GoszakupHttpClientTest {

    static HttpServer server;
    static volatile String lastPath;
    static volatile String lastRequestBody;
    static volatile String nextBody = "{}";
    static GoszakupHttpClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            lastPath = ex.getRequestURI().getPath();
            lastRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b = nextBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        client = new GoszakupHttpClient(new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/v2", "test-token", 50);
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @Test
    void fetchSubject_requestsBiinPath_andParsesObject() {
        nextBody = """
            {"pid":2787,"bin":"971240005114","name_ru":"КГУ Школа-гимназия",
             "address":[{"kato_code":"352810000","address":"Карагандинская область, г.Шахтинск"}]}
            """;
        SubjectDto s = client.fetchSubject("971240005114");
        // по БИН ищет /subject/biin/{биин}; /subject/{id} — это поиск по внутреннему id
        assertThat(lastPath).isEqualTo("/v2/subject/biin/971240005114");
        assertThat(s).isNotNull();
        assertThat(s.getNameRu()).isEqualTo("КГУ Школа-гимназия");
    }

    @Test
    void fetchSubject_unknownBiin_returns200EmptyArray_treatedAsNull() {
        nextBody = "[]"; // живой API на неизвестный БИН отвечает 200 и [], не 404
        assertThat(client.fetchSubject("000000000000")).isNull();
    }

    @Test
    void fetchKatoPage_requestsRefKato_andParsesParts() {
        nextBody = """
            {"total":16844,"next_page":"/v2/refs/ref_kato?page=next&search_after=791510000",
             "items":[{"ab":"27","cd":"10","ef":"10","hij":"000","k":1,"name_ru":"г.Уральск","level_":2}]}
            """;
        var page = client.fetchKatoPage(null);
        assertThat(lastPath).isEqualTo("/v2/refs/ref_kato");
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).code()).isEqualTo("271010000");
        assertThat(page.getNextPage()).contains("search_after=791510000");
    }

    @Test
    void fetchTrdBuyByKato_postsV3Graphql_andParsesAliasedItems() {
        // v3 GraphQL: алиасы в запросе приводят ответ к snake_case v2 → парсится тем же TrdBuyDto
        nextBody = """
            {"data":{"TrdBuy":[{"id":17276688,"number_anno":"17276688-1","name_ru":"Аппарат УЗИ",
                                "org_bin":"971240005114","ref_buy_status_id":220,"system_id":3,
                                "publish_date":"2026-07-02 03:55:46","total_sum":500000}]},
             "extensions":{"pageInfo":{"hasNextPage":true,"lastId":17276688}}}
            """;
        var page = client.fetchTrdBuyPageByKato(java.util.List.of("271010000", "274430300"), null);
        assertThat(lastPath).isEqualTo("/v3/graphql");
        assertThat(lastRequestBody).contains("271010000").contains("kato");
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getNumberAnno()).isEqualTo("17276688-1");
        assertThat(page.getItems().get(0).effectiveBin()).isEqualTo("971240005114");
        assertThat(page.getNextAfter()).isEqualTo(17276688L);
    }

    @Test
    void fetchTrdBuyByKato_lastPage_noNextAfter() {
        nextBody = """
            {"data":{"TrdBuy":[{"id":5,"number_anno":"5-1","name_ru":"x","publish_date":"2026-07-01 10:00:00"}]},
             "extensions":{"pageInfo":{"hasNextPage":false,"lastId":5}}}
            """;
        var page = client.fetchTrdBuyPageByKato(java.util.List.of("271010000"), 100L);
        assertThat(lastRequestBody).contains("\"a\":100");
        assertThat(page.getNextAfter()).isNull();
    }
}
