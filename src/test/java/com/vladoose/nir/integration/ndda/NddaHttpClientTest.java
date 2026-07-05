package com.vladoose.nir.integration.ndda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Живые формы ответов oldregister.ndda.kz (сняты 2026-07-05; стаб на JDK HttpServer, без сети). */
class NddaHttpClientTest {

    static HttpServer server;
    static volatile String lastPath;
    static volatile String lastQuery;
    static volatile String lastRequestBody;
    static volatile String nextBody = "{}";
    static volatile int nextStatus = 200;
    static NddaHttpClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            lastPath = ex.getRequestURI().getPath();
            lastQuery = ex.getRequestURI().getQuery();
            lastRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b = nextBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(nextStatus, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        client = new NddaHttpClient(new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/register-backend");
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @BeforeEach
    void resetStatus() { nextStatus = 200; }

    @Test
    void resolveId_postsListFilteredByRegNumber_andReturnsId() {
        nextBody = """
            [{"id":182621,"reg_number":"РК МИ (ИМН)-0№031074",
              "name":"Раствор офтальмологический вискоэластичный Адгевиск",
              "producerNameRu":"Гротекс","countryNameRu":"РОССИЯ",
              "purpose":null,"shortTechnicalCharacteristicsRu":null}]
            """;
        Long id = client.resolveId("РК МИ (ИМН)-0№031074");
        assertThat(lastPath).isEqualTo("/register-backend/RegisterService/list");
        assertThat(lastRequestBody).contains("\"regNumber\":\"РК МИ (ИМН)-0№031074\"");
        assertThat(lastRequestBody).contains("\"regTypeId\":2");
        assertThat(id).isEqualTo(182621L);
    }

    @Test
    void resolveId_unknownRegNumber_returns200EmptyArray_treatedAsNull() {
        nextBody = "[]"; // живой API на неизвестный № РУ отвечает 200 и [], не 404
        assertThat(client.resolveId("РК МИ (ИМН)-0№999999")).isNull();
    }

    @Test
    void fetchDetail_getsMtMainGetById_andParsesDescriptionFields() {
        // живая форма карточки: ключевой набор полей (термин вида МИ приходит в termName_rus)
        nextBody = """
            {"id":182621,"regNumber":"РК МИ (ИМН)-0№031074",
             "tradeName":"Раствор офтальмологический вискоэластичный Адгевиск",
             "purpose":"Адгевиск поддерживает глубину передней камеры и улучшает визуализацию",
             "useArea":"Область применения – офтальмологическая хирургия",
             "degreeRiskName":"Класс 2 б – с повышенной степенью риска",
             "shortTechnicalCharacteristicsRu":"Прозрачный вязкий раствор. рН – 7,0 – 7,5; вязкость – 40 000 мПа·с",
             "shortTechnicalCharacteristicsKz":"Мөлдір тұтқыр ерітінді",
             "comments":"Хранить при температуре от + 2 °С до + 8 °С",
             "termName_rus":"Материал для замещения водянистой влаги, интраоперационное",
             "termDefinition":"Неимплантируемое искусственное вискоэластичное вещество",
             "nmirkCode":259124,"sterilitySign":true}
            """;
        NddaDetailDto d = client.fetchDetail(182621L);
        assertThat(lastPath).isEqualTo("/register-backend/RegisterService/MtMainGetById");
        assertThat(lastQuery).isEqualTo("Id=182621");
        assertThat(d.getPurpose()).startsWith("Адгевиск поддерживает");
        assertThat(d.getUseArea()).contains("офтальмологическая хирургия");
        assertThat(d.getDegreeRiskName()).isEqualTo("Класс 2 б – с повышенной степенью риска");
        assertThat(d.getShortTechnicalCharacteristicsRu()).contains("40 000 мПа·с");
        assertThat(d.getTermNameRus()).startsWith("Материал для замещения");
        assertThat(d.getTermDefinition()).startsWith("Неимплантируемое");
    }

    @Test
    void non200_throwsUpstreamException() {
        nextStatus = 500;
        nextBody = "oops";
        assertThatThrownBy(() -> client.fetchDetail(1L)).isInstanceOf(UpstreamException.class);
    }
}
