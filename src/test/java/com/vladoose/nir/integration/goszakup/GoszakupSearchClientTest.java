package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.goszakup.dto.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Поиск по стране на v3 (спека 2026-10-19-product-watch §3, §7.2): формы запросов и ответов, сняты живьём 2026-10-19. */
class GoszakupSearchClientTest {

    HttpServer server;
    final List<String> bodies = new ArrayList<>();

    static final String LOTS = """
        {"data":{"Lots":[{"id":43584637,"lotNumber":"88111123-ОИ3","nameRu":"Отсасыватель","descriptionRu":"для аспирации жидкого содержимого",
          "count":1,"amount":3714285.71,"refLotStatusId":220,"lastUpdateDate":"2026-10-16 19:32:08","customerBin":"080740007819",
          "customerNameRu":"ГКП на ПХВ \\"Городская многопрофильная больница № 2\\" акимата города Астана","trdBuyNumberAnno":"17802320-1",
          "TrdBuy":{"id":17802320,"numberAnno":"17802320-1","nameRu":"Аппарат для аспирации дыма","orgBin":"080740007819",
            "orgNameRu":"ГКП на ПХВ \\"Городская многопрофильная больница № 2\\" акимата города Астана","totalSum":3714285.71,
            "publishDate":"2026-10-16 19:32:08","endDate":"2026-10-20 09:00:08","refBuyStatusId":220,"refTradeMethodsId":6,"systemId":3,
            "RefTradeMethods":{"nameRu":"Из одного источника по несостоявшимся закупкам"}}}]},
         "extensions":{"pageInfo":{"hasNextPage":true,"lastId":43584637}}}""";
    static final String BUYS = """
        {"data":{"TrdBuy":[{"id":17802320,"numberAnno":"17802320-1","nameRu":"Аппарат для аспирации дыма","orgBin":"080740007819",
          "totalSum":3714285.71,"publishDate":"2026-10-16 19:32:08","endDate":"2026-10-20 09:00:08","refBuyStatusId":220,
          "refTradeMethodsId":6,"systemId":3,"RefTradeMethods":{"nameRu":"Из одного источника по несостоявшимся закупкам"}}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":17802320}}}""";
    static final String PLANS = """
        {"data":{"Plans":[{"id":88053701,"plnPointYear":2026,"refMonthsId":9,"subjectBiin":"990240006109",
          "subjectNameRu":"ГКП \\"Айтекебийская районная больница\\"","nameRu":"Электрокардиограф","descRu":"3-6 канальный",
          "extraDescRu":"встроенный термопринтер","count":4,"price":1480000,"amount":5920000,"refPlnPointStatusId":2,
          "RefUnits":{"nameRu":"Штука"},"RefTradeMethods":{"nameRu":"Электронный магазин"},"RefMonths":{"nameRu":"Сентябрь"},
          "RefPlnPointStatus":{"nameRu":"Утвержден"},
          "PlansKato":[{"fullDeliveryPlaceNameRu":"Актюбинская область, Айтекебийский район, с.Темирбека Жургенева"}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":88053701}}}""";
    static final String CONTRACTS = """
        {"data":{"Contract":[{"supplierBiin":"123456789012","contractSum":3650000,"contractSumWnds":3832500,"signDate":"2026-09-12 10:00:00","refContractStatusId":250}]}}""";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/graphql", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            String reply = body.contains("forbidden") ? null
                    : body.contains("Contract(") ? CONTRACTS
                    : body.contains("Plans(") ? PLANS
                    : body.contains("Lots(") ? LOTS : BUYS;
            int code = reply == null ? 403 : 200;
            byte[] b = (reply == null ? "{}" : reply).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private GoszakupSearchClient client() {
        return new GoszakupSearchHttpClient(new GoszakupHttpClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v2", "tok", 50));
    }

    @Test
    void searchLots_filtersAndNestedAnnouncement() {
        SearchPage<FoundLot> p = client().searchLots("отсасыватель", List.of(190, 210, 220, 230, 240),
                LocalDate.of(2026, 8, 21), LocalDate.of(2026, 10, 21), null);
        String body = bodies.get(0);
        assertThat(body).contains("nameDescriptionRu").contains("отсасыватель").contains("refLotStatusId").contains("lastUpdateDate")
                .contains("2026-08-21").contains("2026-10-21");
        assertThat(p.nextAfter()).isEqualTo(43584637L);
        FoundLot l = p.items().get(0);
        assertThat(l.nameRu()).isEqualTo("Отсасыватель");
        assertThat(l.announcement().nameRu()).isEqualTo("Аппарат для аспирации дыма");
        assertThat(l.announcement().tradeMethodName()).isEqualTo("Из одного источника по несостоявшимся закупкам");
        assertThat(l.announcement().toTrdBuy().getEndDate()).isEqualTo("2026-10-20 09:00:08");
    }

    @Test
    void searchAnnouncements_commonNameNotNameRu() {
        SearchPage<FoundAnnouncement> p = client().searchAnnouncements("аспирации", List.of(210, 220), null, null, null);
        assertThat(bodies.get(0)).contains("commonName").doesNotContain("\"nameRu\":\"аспирации\"").doesNotContain("publishDate:$");
        assertThat(p.items()).extracting(FoundAnnouncement::numberAnno).containsExactly("17802320-1");
        assertThat(p.nextAfter()).isNull();
    }

    @Test
    void plansAndContracts() {
        FoundPlanPoint pp = client().searchPlans("электрокардиограф", List.of(2026, 2027), List.of(2), null).items().get(0);
        assertThat(pp.month()).isEqualTo(9);
        assertThat(pp.unit()).isEqualTo("Штука");
        assertThat(pp.deliveryPlace()).startsWith("Актюбинская область");
        assertThat(pp.statusName()).isEqualTo("Утвержден");
        assertThat(client().contracts("17578819-1")).extracting(ContractInfo::supplierBiin).containsExactly("123456789012");
    }

    @Test
    void forbidden_statusInException() {
        assertThatThrownBy(() -> client().searchLots("forbidden", null, null, null, null))
                .isInstanceOf(GoszakupCallException.class)
                .satisfies(e -> assertThat(((GoszakupCallException) e).status()).isEqualTo(403));
    }
}
