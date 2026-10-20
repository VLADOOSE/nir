package com.vladoose.nir.integration.ndda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** РУ по номеру: сначала действующие (regPeriod 1), потом недействующие (regPeriod 2) — живые формы ответа 2026-10-20. */
class NddaRegistrationClientTest {

    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    volatile String active = "[]", inactive = "[]";
    NddaHttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            byte[] b = (body.contains("\"regPeriod\":1") ? active : inactive).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        client = new NddaHttpClient(new ObjectMapper(), "http://localhost:" + server.getAddress().getPort() + "/register-backend");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void active_firstList() {
        active = """
            [{"id":153163,"reg_number":"РК-МТ-7№010893","name":"Аппарат электрохирургический ЭХВЧ-80","producerNameRu":"ФОТЕК",
              "countryNameRu":"РОССИЯ","reg_date":"2019-11-25T00:00:00","expiration_date":null,"unlimited_sign":true}]""";
        NddaRegistration r = client.findRegistration("РК-МТ-7№010893");
        assertThat(r.active()).isTrue();
        assertThat(r.id()).isEqualTo(153163L);
        assertThat(r.unlimited()).isTrue();
        assertThat(r.producer()).isEqualTo("ФОТЕК");
        assertThat(bodies).hasSize(1);
    }

    @Test
    void expired_secondList() {
        inactive = """
            [{"id":172316,"reg_number":"РК-МТ-5№022493","name":"Отсасыватель медицинский хирургический ОМХ-5/80-01 «АКСИОН»",
              "producerNameRu":"Концерн Аксион","countryNameRu":"РОССИЯ","reg_date":"2021-06-28T00:00:00",
              "expiration_date":"2026-06-28T00:00:00","unlimited_sign":false,"reg_term":5}]""";
        NddaRegistration r = client.findRegistration("РК-МТ-5№022493");
        assertThat(r.active()).isFalse();
        assertThat(r.expirationDate()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).contains("\"regPeriod\":2");
    }

    @Test
    void notFound_null_andOtherNumberIgnored() {
        inactive = "[{\"id\":1,\"reg_number\":\"РК-МТ-5№000001\"}]";   // list вернул чужой номер — не наш
        assertThat(client.findRegistration("РК-МТ-5№022493")).isNull();
    }
}
