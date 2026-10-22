package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static com.vladoose.nir.service.supplierreply.ReplyFixtures.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Экран «Ответ поставщика»: вид, гард рынка, права, загрузка, страницы PDF, скачивание, роли колонок, удаление. */
@SpringBootTest
@Transactional
class SupplierReplyApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired SupplierReplyService replies;
    MockMvc mvc;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        pr = newRequest();
    }

    /** Частная заявка KZ на две позиции и запрос цен к ней (SENT). */
    PriceRequest newRequest() {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(
                input("Кушетка медицинская смотровая", null, 2, null, null, null),
                input("Столик процедурный", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Ответ API " + System.nanoTime()).build());
        PriceRequest p = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").sentAt(OffsetDateTime.now()).build());
        for (TenderLot l : t.getLots()) p.getItems().add(items.save(PriceRequestItem.builder().priceRequest(p).tenderLot(l).requestedQuantity(l.getQuantity()).build()));
        MarketContext.clear();
        return p;
    }

    @AfterEach void clear() { MarketContext.clear(); }

    void ingest() {
        MarketContext.set(Market.KZ);
        ReplyTable table = new ReplyTable("Лист1", 4, 4, "KZT", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена, тг", LineField.PRICE), new ReplyTable.Column(3, "Сумма, тг", LineField.SUM)),
                List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "1", "95 000", "95 000")),
                        new ReplyTable.Row(6, List.of("Столик процедурный СП-02", "1", "48 500", "48 500")),
                        new ReplyTable.Row(7, List.of("Доставка", "1", "5 000", "5 000"))));
        replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП.xlsx", table)), null);
        MarketContext.clear();
    }

    @Test
    void viewShowsPositionsRowsFlagsAndTable() throws Exception {
        ingest();
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.supplierName", startsWith("ZZ Ответ API")))
                .andExpect(jsonPath("$.positions[0].auto").value(true))
                .andExpect(jsonPath("$.positions[0].row.fileRow").value(5))
                .andExpect(jsonPath("$.positions[0].flags", hasItem(startsWith("кол-во: 1"))))
                .andExpect(jsonPath("$.table.columns[2].role").value("PRICE"))
                .andExpect(jsonPath("$.counts.unverified").value(2))
                .andExpect(jsonPath("$.counts.unassignedRows").value(1))
                .andExpect(jsonPath("$.sources[0].type").value("FILE"));
    }

    /**
     * Скидка только в итоге файла (КП «Рикты»): итог «со скидкой» меньше суммы строк — предупреждение на экране сверки;
     * итог, равный сумме строк, — без предупреждения; итоговые строки — под таблицей файла.
     */
    @Test
    void discountOnlyInTotal_shownWithRowsSum() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, тг", "Сумма, тг"),
                List.of("Кушетка медицинская смотровая КМС-01", "2", "95 000", "190 000"),
                List.of("Столик процедурный СП-02", "1", "48 500", "48 500"),
                List.of("Итого", "", "", "238 500"),
                List.of("Итого с учетом скидки 10%", "", "", "214 650")));
        mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП со скидкой.xlsx", "application/octet-stream", xlsx))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.totals[0].label").value("Итого"))
                .andExpect(jsonPath("$.table.totals[1].amount").value(214650.0))
                .andExpect(jsonPath("$.table.discount.label").value("Итого с учетом скидки 10%"))
                .andExpect(jsonPath("$.table.discount.amount").value(214650.0))
                .andExpect(jsonPath("$.table.discount.rowsSum").value(238500.0));
    }

    @Test
    void totalEqualsRows_noDiscountNotice() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, тг", "Сумма, тг"),
                List.of("Кушетка медицинская смотровая КМС-01", "2", "95 000", "190 000"),
                List.of("Итого (скидка учтена в ценах)", "", "", "190 000")));
        mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП.xlsx", "application/octet-stream", xlsx))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.totals[0].amount").value(190000.0))
                .andExpect(jsonPath("$.table.discount").doesNotExist());
    }

    /** Запрос на одну позицию — как в КП-буклете АМИКО: цены из текста документа подсказкой, файл без таблицы выбран сам. */
    PriceRequest singleRequest() {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Флюорограф цифровой", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Буклет " + System.nanoTime()).build());
        PriceRequest p = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").sentAt(OffsetDateTime.now()).build());
        for (TenderLot l : t.getLots()) p.getItems().add(items.save(PriceRequestItem.builder().priceRequest(p).tenderLot(l).requestedQuantity(1).build()));
        MarketContext.clear();
        return p;
    }

    static byte[] booklet() throws Exception {
        org.apache.poi.xwpf.usermodel.XWPFDocument d = new org.apache.poi.xwpf.usermodel.XWPFDocument();
        d.createParagraph().createRun().setText("Флюорограф малодозовый цифровой сканирующий ФМцс-«ПроСкан»");
        d.createParagraph().createRun().setText("Стоимость оборудования со скидкой – 5 000 000 рублей. Ввод в эксплуатацию – 200 000 рублей.");
        return ReplyTableReaderTest.docx(d);
    }

    @Test
    void bookletWithoutTable_singlePosition_textPricesHint() throws Exception {
        PriceRequest one = singleRequest();
        mvc.perform(multipart("/api/price-requests/" + one.getId() + "/files")
                        .file(new MockMultipartFile("file", "ПроСкан-2000.docx", "application/octet-stream", booklet()))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        // без fileId экран сам выбирает файл без таблицы — иначе подсказку не видно, пока не нажать на чип
        mvc.perform(get("/api/price-requests/" + one.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").isNumber())
                .andExpect(jsonPath("$.textPrices[0].amount").value(5000000.0))
                .andExpect(jsonPath("$.textPrices[0].currency").value("RUB"))
                .andExpect(jsonPath("$.textPrices[0].text").value("Стоимость оборудования со скидкой – 5 000 000 рублей"))
                .andExpect(jsonPath("$.textPrices[1].amount").value(200000.0))
                .andExpect(jsonPath("$.positions[0].price").doesNotExist());   // подсказка, не подстановка
    }

    @Autowired SupplierReplyFiles replyFiles;

    /** Итог для Telegram: цены из текста буклета — только у запроса на одну позицию. */
    @Test
    void ingestOutcome_textPricesOnlyForSinglePosition() throws Exception {
        PriceRequest one = singleRequest();
        MarketContext.set(Market.KZ);
        SupplierReplyService.ReplyOutcome single = replies.ingest(priceRequests.findById(one.getId()).orElseThrow(),
                List.of(replyFiles.prepare("ПроСкан-2000.docx", "x", booklet())), null);
        assertThat(single.textPricesFile()).isEqualTo("ПроСкан-2000.docx");
        assertThat(single.textPrices()).hasSize(2);
        SupplierReplyService.ReplyOutcome many = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(),
                List.of(replyFiles.prepare("ПроСкан-2000.docx", "x", booklet())), null);
        assertThat(many.textPrices()).isEmpty();
        MarketContext.clear();
    }

    /** Запрос на несколько позиций: «Стоимость 1 390 000 тенге» в буклете не говорит, к какой позиции, — подсказки нет. */
    @Test
    void bookletWithoutTable_severalPositions_noHint() throws Exception {
        mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "ПроСкан-2000.docx", "application/octet-stream", booklet()))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.textPrices").isEmpty());
    }

    @Test
    void otherMarketIs404_operatorCannotWrite() throws Exception {
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/price-requests/" + pr.getId() + "/verify").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadXlsx_appliesPrices_downloadIsAttachment() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, тг"),
                List.of("Кушетка медицинская смотровая КМС-01", "2", "95 000")));
        String body = mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП WhatsApp.xlsx", "application/octet-stream", xlsx))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].price").value(95000.0))
                .andExpect(jsonPath("$.sources[0].uploaded").value(true))
                .andReturn().getResponse().getContentAsString();
        Long fileId = com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.fileId").longValue();

        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        // файл чужого рынка не отдаётся: гард запроса стоит и у скачивания, не только у вида экрана
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void pdfPageIsPng_beyondCountIs404() throws Exception {
        String body = mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП.pdf", "application/pdf", pdfBytes(2)))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(body, "$.sources[0].fileId");
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/pages/1").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/pages/5").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void columnsPut_reappliesPrices() throws Exception {
        ingest();
        String view = mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(view, "$.fileId");
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/columns").header("X-Market", "KZ")
                        .contentType("application/json").content("[{\"index\":2,\"role\":\"IGNORE\"},{\"index\":3,\"role\":\"PRICE\"}]")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.columns[3].role").value("PRICE"));
    }

    @Test
    void emailFileDeleteIs400() throws Exception {
        ingest();
        String view = mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(view, "$.fileId");
        mvc.perform(delete("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    /** Цены файла в рублях на запрос KZ: у подсказки и строк таблицы — валюта файла, экран не выдаёт их за тенге. */
    @Test
    void foreignCurrencyIsShownOnSuggestionsAndRows() throws Exception {
        MarketContext.set(Market.KZ);
        ReplyTable table = new ReplyTable("Лист1", 4, 3, "RUB", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена, руб.", LineField.PRICE)),
                List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "2", "4 700"))));
        replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП.xlsx", table)), null);
        MarketContext.clear();

        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.currency").value("KZT"))
                .andExpect(jsonPath("$.positions[0].price").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].suggestion.currency").value("RUB"))
                .andExpect(jsonPath("$.table.rows[0].currency").value("RUB"));
    }

    /** Цена, поправленная руками после курса, — пометка «цена не по курсу …» с ценой по строке (ревью ветки курса). */
    @Test
    void handEditedPriceAfterRate_flagSaysNotByRate() throws Exception {
        MarketContext.set(Market.KZ);
        ReplyTable rub = new ReplyTable("Лист1", 4, 3, "RUB", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена, руб.", LineField.PRICE)),
                List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "2", "10 000"))));
        Long fileId = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб.xlsx", rub)), null)
                .fileIds().get(0);
        replies.setRate(pr.getId(), fileId, new java.math.BigDecimal("6"));
        PriceRequestItem it = items.findById(pr.getItems().get(0).getId()).orElseThrow();
        it.setResponsePrice(new java.math.BigDecimal("59000"));
        it.setResponseAuto(false);
        items.flush();
        MarketContext.clear();

        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.positions[0].flags", hasItem(allOf(containsString("цена не по курсу 6"), containsString("60")))))
                .andExpect(jsonPath("$.positions[0].flags", not(hasItem(containsString("цена файла")))));
    }

    /**
     * Поле курса (2026-10-16): у файла в чужой валюте — блок rate; PUT курса пересчитывает цены (💡, строка, пометка с
     * курсом), у строк — пересчитанная цена, в ответе — итог (сколько подставлено). Оператор курс не ставит, чужой
     * рынок — 404. Второй файл той же валюты в этом запросе (исправленное КП) берёт курс сам; файл другого запроса —
     * подсказку: последний курс на рынке и когда он вписан.
     */
    @Test
    void rateField_convertsPrices_inheritsInRequest_suggestsOnMarket() throws Exception {
        MarketContext.set(Market.KZ);
        ReplyTable rub = new ReplyTable("Лист1", 4, 3, "RUB", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена, руб.", LineField.PRICE)),
                List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "2", "10 000")),
                        new ReplyTable.Row(6, List.of("Столик процедурный СП-02", "1", "5 000"))));
        Long first = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб.xlsx", rub)), null)
                .fileIds().get(0);
        MarketContext.clear();
        String url = "/api/price-requests/" + pr.getId() + "/files/" + first + "/rate";

        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.rate.currency").value("RUB"))
                .andExpect(jsonPath("$.rate.value").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].suggestion.converted").value(nullValue()));
        mvc.perform(put(url).contentType("application/json").content("{\"rate\": 6}").header("X-Market", "KZ")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(put(url).contentType("application/json").content("{\"rate\": 6}").header("X-Market", "RF")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());

        mvc.perform(put(url).contentType("application/json").content("{\"rate\": 6}").header("X-Market", "KZ")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.keptManual").value(0))
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.view.rate.value").value(6.0))
                .andExpect(jsonPath("$.view.positions[0].price").value(60000.0))
                .andExpect(jsonPath("$.view.positions[0].auto").value(true))
                .andExpect(jsonPath("$.view.positions[0].row.converted").value(60000.0))
                .andExpect(jsonPath("$.view.positions[0].flags", empty()))   // пересчёт виден в строке файла — не «расхождение»
                .andExpect(jsonPath("$.view.table.rows[1].converted").value(30000.0));

        MarketContext.set(Market.KZ);
        ReplyTable again = new ReplyTable("Лист1", 4, 3, "RUB", rub.columns(),
                List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "2", "11 000"))));
        Long second = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб 2.xlsx", again)), null)
                .fileIds().get(0);
        MarketContext.clear();
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").param("fileId", String.valueOf(second))
                        .header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.rate.value").value(6.0))
                .andExpect(jsonPath("$.positions[0].price").value(66000.0));

        PriceRequest other = newRequest();
        MarketContext.set(Market.KZ);
        Long third = replies.ingest(priceRequests.findById(other.getId()).orElseThrow(), List.of(parsed("КП руб 3.xlsx", rub)), null)
                .fileIds().get(0);
        MarketContext.clear();
        mvc.perform(get("/api/price-requests/" + other.getId() + "/reply").param("fileId", String.valueOf(third))
                        .header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.rate.value").value(nullValue()))
                .andExpect(jsonPath("$.rate.suggestion").value(6.0))
                .andExpect(jsonPath("$.rate.suggestedAt").value(notNullValue()))
                .andExpect(jsonPath("$.positions[0].price").value(nullValue()));
    }

    /**
     * Валюта цен файла (2026-10-18): PUT — оператору 403, чужой рынок — 404, неизвестная валюта — 400; админ — вид и
     * итог: валюта таблицы, источник «вручную» (кто и когда), несверенные цены сняты, дальше — поле курса.
     */
    @Test
    void currencyPut_rightsMarketValidation_viewAndResult() throws Exception {
        ingest();
        String view = mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.table.currencyOrigin.source").value("TABLE"))
                .andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(view, "$.fileId");
        String url = "/api/price-requests/" + pr.getId() + "/files/" + fileId + "/currency";

        mvc.perform(put(url).contentType("application/json").content("{\"currency\":\"RUB\"}").header("X-Market", "KZ")
                        .with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(put(url).contentType("application/json").content("{\"currency\":\"RUB\"}").header("X-Market", "RF")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(put(url).contentType("application/json").content("{\"currency\":\"GBP\"}").header("X-Market", "KZ")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());

        mvc.perform(put(url).contentType("application/json").content("{\"currency\":\"RUB\"}").header("X-Market", "KZ")
                        .with(user("vlad").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.removed").value(2))
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.view.table.currency").value("RUB"))
                .andExpect(jsonPath("$.view.table.currencyOrigin.source").value("MANUAL"))
                .andExpect(jsonPath("$.view.table.currencyOrigin.by").value("vlad"))
                .andExpect(jsonPath("$.view.table.currencyOrigin.at").value(notNullValue()))
                .andExpect(jsonPath("$.view.rate.currency").value("RUB"))
                .andExpect(jsonPath("$.view.positions[0].price").value(nullValue()));
    }

    /**
     * Откуда валюта — в виде экрана: из текста — TEXT и фрагмент; не названа — NONE; разные валюты — MIXED и без поля
     * курса (пересчитывать нечего, сначала валюту выбирают).
     */
    @Test
    void currencyOriginInView_textNoneMixed() throws Exception {
        MarketContext.set(Market.KZ);
        List<ReplyTable.Column> cols = List.of(new ReplyTable.Column(0, "Наименование", LineField.NAME),
                new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY), new ReplyTable.Column(2, "Цена", LineField.PRICE));
        List<ReplyTable.Row> rows = List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "2", "4 700")));
        ReplyTable text = new ReplyTable("таблица 1", 1, 3, "RUB", cols, rows,
                new ReplyTable.CurrencyFrom(ReplyCurrencySource.TEXT, List.of("…на сумму 9 400,00 руб.…"), null, null));
        ReplyTable none = new ReplyTable("таблица 1", 1, 3, null, cols, rows,
                new ReplyTable.CurrencyFrom(ReplyCurrencySource.NONE, List.of(), null, null));
        ReplyTable mixed = new ReplyTable("таблица 1", 1, 3, MoneyText.MIXED, cols, rows,
                new ReplyTable.CurrencyFrom(ReplyCurrencySource.MIXED, List.of("Цены в рублях.", "Оплата в тенге."), null, null));
        PriceRequest fresh = priceRequests.findById(pr.getId()).orElseThrow();
        Long t = replies.ingest(fresh, List.of(parsed("text.docx", text)), null).fileIds().get(0);
        Long n = replies.ingest(fresh, List.of(parsed("none.docx", none)), null).fileIds().get(0);
        Long m = replies.ingest(fresh, List.of(parsed("mixed.docx", mixed)), null).fileIds().get(0);
        MarketContext.clear();
        String base = "/api/price-requests/" + pr.getId() + "/reply";

        mvc.perform(get(base).param("fileId", String.valueOf(t)).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.table.currencyOrigin.source").value("TEXT"))
                .andExpect(jsonPath("$.table.currencyOrigin.texts[0]").value("…на сумму 9 400,00 руб.…"))
                .andExpect(jsonPath("$.rate.currency").value("RUB"));
        mvc.perform(get(base).param("fileId", String.valueOf(n)).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.table.currency").value(nullValue()))
                .andExpect(jsonPath("$.table.currencyOrigin.source").value("NONE"))
                .andExpect(jsonPath("$.rate").value(nullValue()));
        mvc.perform(get(base).param("fileId", String.valueOf(m)).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.table.currencyOrigin.source").value("MIXED"))
                .andExpect(jsonPath("$.table.currencyOrigin.texts", hasSize(2)))
                .andExpect(jsonPath("$.rate").value(nullValue()));
    }
}
