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

/** Итоги, договоры, цепочки и цены рынка на v3 (спека 2026-10-22-tender-results-market-prices §3): формы сняты живьём 2026-10-22. */
class GoszakupResultsClientTest {

    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    final List<String> downloads = new ArrayList<>();
    int contractCalls;

    static final String BUYS = """
        {"data":{"TrdBuy":[{"id":17758520,"numberAnno":"17758520-1","refBuyStatusId":350,"itogiDatePublic":"2026-10-14 12:54:49",
          "biinSupplier":null,"refTradeMethodsId":3,"RefTradeMethods":{"nameRu":"Запрос ценовых предложений"},
          "Files":[{"nameRu":"Протокол итогов","originalName":"protocol_PI_17758520.pdf","filePath":"PROTO_URL"},
                   {"nameRu":"Техническая спецификация","originalName":"techspec.pdf","filePath":"https://x/techspec"}],
          "Lots":[{"id":43546011,"lotNumber":"88214663-ЗЦП2","refLotStatusId":360,"count":3,"amount":1841700,"pointList":[88214663],
            "customerBin":"110240019426","RefLotsStatus":{"nameRu":"Закупка состоялась"},
            "Plans":[{"refEnstruCode":"309911.100.000001","RefUnits":{"nameRu":"Штука"}}]}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":17758520}}}""";
    static final String CONTRACTS_PAGE1 = """
        {"data":{"Contract":[{"id":25969702,"trdBuyId":17752538,"contractNumber":"070740006294/260209/00","signDate":"2026-10-20 09:28:34",
          "refContractStatusId":190,"supplierBiin":"880808302714","contractSumWnds":43000,
          "ContractUnits":[{"lotId":43533728,"itemPrice":40000,"itemPriceWnds":43000,"quantity":1,"totalSumWnds":43000}]}]},
         "extensions":{"pageInfo":{"hasNextPage":true,"lastId":25969702}}}""";
    static final String CONTRACTS_PAGE2 = """
        {"data":{"Contract":[{"id":25969800,"trdBuyId":17752539,"contractNumber":"2","signDate":"2026-10-21 10:00:00",
          "refContractStatusId":190,"supplierBiin":"880808302715","contractSumWnds":100,
          "ContractUnits":[{"lotId":43533729,"itemPrice":100,"itemPriceWnds":null,"quantity":1,"totalSumWnds":100}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":25969800}}}""";
    static final String ATTEMPTS = """
        {"data":{"Lots":[{"id":43625311,"lotNumber":"88111123-ОИ3","refLotStatusId":360,"pointList":[88111123],"trdBuyId":17802320,
          "trdBuyNumberAnno":"17802320-1","RefLotsStatus":{"nameRu":"Закупка состоялась"},
          "TrdBuy":{"id":17802320,"numberAnno":"17802320-1","publishDate":"2026-10-16 19:32:08","endDate":"2026-10-19 19:32:08","itogiDatePublic":"2026-10-20 09:10:00",
            "biinSupplier":"070326551082","refTradeMethodsId":6,"RefTradeMethods":{"nameRu":"Из одного источника по несостоявшимся закупкам"}}}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":43625311}}}""";
    static final String CLOSED = """
        {"data":{"Lots":[{"id":43000001,"lotNumber":"1-ЗЦП1","nameRu":"Дефибриллятор","descriptionRu":"для терапии нарушений сердечного ритма",
          "count":2,"amount":2800000,"trdBuyId":17000001,"trdBuyNumberAnno":"17000001-1","customerBin":"990240006109",
          "customerNameRu":"ГКП «Больница»","RefTradeMethods":{"nameRu":"Запрос ценовых предложений"},
          "Plans":[{"refEnstruCode":"266011.150.000000","extraDescRu":"бифазный, с монитором","RefUnits":{"nameRu":"Штука"},
            "PlansKato":[{"fullDeliveryPlaceNameRu":"Актюбинская область, г.Актобе"}]}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":43000001}}}""";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v3/graphql", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            String reply = body.contains("Contract(") ? (contractCalls++ == 0 ? CONTRACTS_PAGE1 : CONTRACTS_PAGE2)
                    : body.contains("TrdBuy(") ? BUYS.replace("PROTO_URL", base + "/download/trd_buy/abc")
                    : body.contains("pointList:$") ? ATTEMPTS : CLOSED;
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.createContext("/download/", ex -> {
            downloads.add(ex.getRequestURI().getPath() + "|" + ex.getRequestHeaders().getFirst("Authorization"));
            boolean missing = ex.getRequestURI().getPath().endsWith("/gone");
            byte[] b = "%PDF-1.4 протокол".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(missing ? 404 : 200, missing ? -1 : b.length);
            if (!missing) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.createContext("/v2/subject/biin/", ex -> {
            byte[] b = """
                {"bin":"110240019426","name_ru":"ГКП «Бурлинская РБ»","email":"aksaymed@mail.ru","phone":"31398"}"""
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private GoszakupResultsClient client() {
        return new GoszakupResultsHttpClient(new GoszakupHttpClient(new ObjectMapper(), base() + "/v2", "tok", 50));
    }

    @Test
    void announcements_lotsStatusesPlanAndProtocol() {
        List<ResultBuy> buys = client().announcements(List.of(17758520L));
        assertThat(bodies.get(0)).contains("TrdBuy(filter:{id:$v0}").contains("17758520").contains("itogiDatePublic");
        ResultBuy b = buys.get(0);
        assertThat(b.resultsAt()).isEqualTo("2026-10-14 12:54:49");
        assertThat(b.tradeMethodName()).isEqualTo("Запрос ценовых предложений");
        assertThat(b.protocols()).extracting(ProtocolFileRef::name).containsExactly("protocol_PI_17758520.pdf");
        ResultLot l = b.lots().get(0);
        assertThat(l.statusId()).isEqualTo(360);
        assertThat(l.statusName()).isEqualTo("Закупка состоялась");
        assertThat(l.planPointId()).isEqualTo(88214663L);
        assertThat(l.enstruCode()).isEqualTo("309911.100.000001");
        assertThat(l.unit()).isEqualTo("Штука");
        assertThat(l.count()).isEqualByComparingTo("3");
    }

    @Test
    void contracts_unitPriceWithVatFirst_andAllPages() {
        List<ContractRow> rows = client().contractsByBuyIds(List.of(17752538L, 17752539L));
        assertThat(bodies).hasSize(2);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).units().get(0).unitPrice()).isEqualByComparingTo("43000");
        assertThat(rows.get(1).units().get(0).unitPrice()).as("без цены с НДС — цена без НДС").isEqualByComparingTo("100");
        assertThat(rows.get(0).signDate()).startsWith("2026-10-20");
    }

    @Test
    void attempts_methodSupplierAndPoint() {
        AttemptLot a = client().lotsByPlanPoints(List.of(88111123L)).get(0);
        assertThat(bodies.get(0)).contains("pointList:$v0");
        assertThat(a.tradeMethodId()).isEqualTo(6);
        assertThat(a.supplierBin()).isEqualTo("070326551082");
        assertThat(a.anno()).isEqualTo("17802320-1");
        assertThat(a.planPointId()).isEqualTo(88111123L);
        assertThat(a.publishDate()).isEqualTo("2026-10-16 19:32:08");
        assertThat(a.endDate()).isEqualTo("2026-10-19 19:32:08");
    }

    @Test
    void closedLots_onlyDone_withCategoryUnitPlace() {
        SearchPage<ClosedLot> p = client().closedLots("дефибриллятор", LocalDate.of(2024, 10, 22), LocalDate.of(2026, 10, 22), null);
        assertThat(bodies.get(0)).contains("nameDescriptionRu").contains("360").contains("2024-10-22");
        ClosedLot l = p.items().get(0);
        assertThat(l.enstruCode()).isEqualTo("266011.150.000000");
        assertThat(l.extraDesc()).isEqualTo("бифазный, с монитором");
        assertThat(l.unit()).isEqualTo("Штука");
        assertThat(l.deliveryPlace()).startsWith("Актюбинская область");
        assertThat(l.amount()).isEqualByComparingTo("2800000");
    }

    @Test
    void protocol_onlyFromPlatformHost_withoutToken() {
        byte[] b = client().downloadProtocol(base() + "/download/trd_buy/abc");
        assertThat(new String(b, StandardCharsets.UTF_8)).startsWith("%PDF");
        assertThat(downloads).containsExactly("/download/trd_buy/abc|null");
        assertThat(client().downloadProtocol(base() + "/download/trd_buy/gone")).as("404 — файла нет").isNull();
        assertThatThrownBy(() -> client().downloadProtocol("https://evil.example/download/x")).isInstanceOf(GoszakupCallException.class);
        assertThatThrownBy(() -> client().downloadProtocol(base() + "/v2/subject/biin/1")).isInstanceOf(GoszakupCallException.class);
        assertThat(downloads).hasSize(2);
    }

    @Test
    void subject_phoneAndEmail() {
        SubjectInfo s = client().subject("110240019426");
        assertThat(s.phone()).isEqualTo("31398");
        assertThat(s.email()).isEqualTo("aksaymed@mail.ru");
        assertThat(s.nameRu()).isEqualTo("ГКП «Бурлинская РБ»");
    }
}
