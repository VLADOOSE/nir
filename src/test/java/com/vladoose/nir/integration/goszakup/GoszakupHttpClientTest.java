package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Живые формы ответов goszakup, снятые реальным токеном (стаб на JDK HttpServer, без сети). */
class GoszakupHttpClientTest {

    static HttpServer server;
    static volatile String lastPath;
    static final java.util.Set<String> requestedPaths = ConcurrentHashMap.newKeySet();
    static volatile String lastRequestBody;
    static volatile String nextBody = "{}";
    /** Ответ по «путь?query» (query без «?», пустой — ""); нет в карте — 200 и {@link #nextBody}. */
    static final Map<String, Reply> replies = new ConcurrentHashMap<>();

    record Reply(int status, String body, String location) {}

    static void respond(String path, String query, int status, String body) {
        replies.put(path + "?" + query, new Reply(status, body, null));
    }

    static void respondRedirect(String path, String location) {
        replies.put(path + "?", new Reply(302, "", location));
    }
    static GoszakupHttpClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            lastPath = ex.getRequestURI().getPath();
            requestedPaths.add(lastPath);
            lastRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String q = ex.getRequestURI().getRawQuery();
            Reply r = replies.getOrDefault(lastPath + "?" + (q == null ? "" : q), new Reply(200, nextBody, null));
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (r.location() != null) ex.getResponseHeaders().add("Location", r.location());
            ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
            try (OutputStream os = ex.getResponseBody()) { if (b.length > 0) os.write(b); }
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

    /** JDK 17 бросает ConnectException без текста — в логе прода было «goszakup API недоступно: null». */
    @Test
    void unreachableApi_namesTheFailureInsteadOfNull() throws java.io.IOException {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        GoszakupHttpClient unreachable = new GoszakupHttpClient(new ObjectMapper(),
                "http://127.0.0.1:" + closedPort + "/v2", "test-token", 50);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unreachable.fetchSubject("123456789012"))
                .hasMessage("goszakup API недоступно: ConnectException");
    }

    @Test
    void fetchLots_followsNextPage_untilTotal() {
        respond("/v2/lots/number-anno/17737448-1", "limit=500", 200, """
            {"total":3,"limit":2,"next_page":"/v2/lots/number-anno/17737448-1?page=next&search_after=43500954",
             "items":[{"lot_number":"1","name_ru":"A","count":1,"amount":10},{"lot_number":"2","name_ru":"B","count":1,"amount":10}]}""");
        respond("/v2/lots/number-anno/17737448-1", "page=next&search_after=43500954", 200, """
            {"total":3,"limit":2,"next_page":"","items":[{"lot_number":"3","name_ru":"C","count":1,"amount":10}]}""");
        assertThat(client.fetchLots("17737448-1")).extracting(LotDto::getNameRu).containsExactly("A", "B", "C");
    }

    @Test
    void fetchLots_lessThanTotal_failsNotRetryable() {
        respond("/v2/lots/number-anno/1-1", "limit=500", 200, """
            {"total":5,"limit":500,"next_page":"","items":[{"lot_number":"1","name_ru":"A","count":1,"amount":10}]}""");
        assertThatThrownBy(() -> client.fetchLots("1-1"))
            .isInstanceOfSatisfying(GoszakupCallException.class, e -> {
                assertThat(e.retryable()).isFalse();
                assertThat(e.getMessage()).isEqualTo("goszakup: лоты объявления 1-1 — получено 1 из 5");
            });
    }

    @Test
    void serverError_retryable_clientError_not() {
        respond("/v2/subject/biin/111", "", 503, "{}");
        assertThatThrownBy(() -> client.fetchSubject("111"))
            .isInstanceOfSatisfying(GoszakupCallException.class, e -> assertThat(e.retryable()).isTrue());
        respond("/v2/subject/biin/222", "", 403, "{}");
        assertThatThrownBy(() -> client.fetchSubject("222"))
            .isInstanceOfSatisfying(GoszakupCallException.class, e -> {
                assertThat(e.retryable()).isFalse();
                // путь без хоста: ни адреса площадки, ни query в тексте для оператора
                assertThat(e.getMessage()).isEqualTo("goszakup API 403 на /v2/subject/biin/222");
            });
    }

    @Test
    void redirectNotFollowed_tokenStaysHome() {   // C5 не ухудшаем: 302 → ошибка, а не запрос на чужой хост
        // цель редиректа живая и ответила бы валидным субъектом — значит, отказ только от того, что не пошли
        respond("/steal", "", 200, "{\"bin\":\"333\",\"name_ru\":\"чужой\"}");
        respondRedirect("/v2/subject/biin/333", "http://localhost:" + server.getAddress().getPort() + "/steal");
        assertThatThrownBy(() -> client.fetchSubject("333"))
            .isInstanceOfSatisfying(GoszakupCallException.class, e -> assertThat(e.retryable()).isFalse());
        assertThat(requestedPaths).doesNotContain("/steal");
    }
}
