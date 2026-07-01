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
    static volatile String nextBody = "{}";
    static GoszakupHttpClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            lastPath = ex.getRequestURI().getPath();
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
}
