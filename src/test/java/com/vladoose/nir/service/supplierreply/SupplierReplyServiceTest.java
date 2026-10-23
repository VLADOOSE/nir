package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ReplyColumnRole;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.supplierreply.SupplierReplyService.ReplyOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.vladoose.nir.service.supplierreply.ReplyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SupplierReplyServiceTest {

    @Autowired SupplierReplyService service;
    @Autowired SupplierReplyFileRepository files;
    @Autowired PriceRequestRepository requests;
    @Autowired PriceRequestItemRepository items;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired HeaderSynonymRepository synonyms;
    @Autowired EntityManager em;
    @Autowired CurrencyRateRepository ours;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        ours.deleteAllInBatch();   // курсы файла — без нашего курса (V44): живая проверка оставляет его строки в nirdb
        pr = request(Market.KZ);
    }

    /** Заявка рынка m на три позиции и запрос цен к ней (SENT); MarketContext остаётся на m. */
    PriceRequest request(Market m) {
        MarketContext.set(m);
        Tender t = Tender.builder().tenderNumber("ZZSVC-" + System.nanoTime()).status("NEW").source(Source.PRIVATE_REQUEST).build();
        for (String n : List.of("Скальпель офтальмологический MSL24", "Скальпель офтальмологический MST15", "Материал шовный нейлон 2056")) {
            t.getLots().add(TenderLot.builder().tender(t).equipName(n).quantity(2).build());
        }
        t = tenders.save(t);
        Distributor d = distributors.save(Distributor.builder().name("ZZSVC Дистр " + System.nanoTime()).build());
        PriceRequest p = PriceRequest.builder().tender(t).distributor(d).status("SENT").build();
        for (TenderLot l : t.getLots()) p.getItems().add(PriceRequestItem.builder().priceRequest(p).tenderLot(l).requestedQuantity(2).build());
        return requests.save(p);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ReplyTable kzTable() {
        return table("KZT", "Скальпели офтальмологические: Скальпель MSL24", "4 700",
                "Скальпели офтальмологические: Скальпель MST15", "4 200",
                "Материал шовный стерильный нейлон 2056", "11 750", "Доставка", "5 000");
    }

    PriceRequestItem item(int i) {
        return items.findById(pr.getItems().get(i).getId()).orElseThrow();
    }

    @Test
    void ingestAppliesConfidentPricesWithAutoMark() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);

        assertThat(o.applied()).isEqualTo(3);
        assertThat(o.positions()).isEqualTo(3);
        assertThat(o.fileIds()).hasSize(1);
        PriceRequestItem first = item(0);
        assertThat(first.getResponsePrice()).isEqualByComparingTo("4700");
        assertThat(first.isResponseAuto()).isTrue();
        assertThat(first.getResponseFileId()).isEqualTo(o.fileIds().get(0));
        assertThat(first.getResponseFileRow()).isEqualTo(5);
        assertThat(first.getResponseNote()).startsWith("Из файла «kp.xlsx», строка 5.");
    }

    @Test
    void manualPriceKept_ownAutoPriceReplaced() {
        PriceRequestItem manual = pr.getItems().get(1);
        manual.setResponsePrice(new BigDecimal("999"));
        PriceRequestItem auto = pr.getItems().get(0);
        auto.setResponsePrice(new BigDecimal("100"));
        auto.setResponseAuto(true);
        em.flush();

        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);

        assertThat(o.manualKept()).isEqualTo(1);
        assertThat(item(1).getResponsePrice()).isEqualByComparingTo("999");
        assertThat(item(1).isResponseAuto()).isFalse();
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
    }

    @Test
    void sameFileTwiceStoredOnce() {
        var f = parsed("kp.xlsx", kzTable());
        service.ingest(pr, List.of(f), null);
        ReplyOutcome again = service.ingest(pr, List.of(f), null);
        assertThat(again.fileIds()).isEmpty();
        assertThat(files.findInfo(pr.getId())).hasSize(1);
    }

    @Test
    void foreignCurrencyNotApplied() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", table("RUB",
                "Скальпели офтальмологические: Скальпель MSL24", "4700"))), null);
        assertThat(o.applied()).isZero();
        assertThat(o.foreignCurrency()).isEqualTo("RUB");
        assertThat(item(0).getResponsePrice()).isNull();
    }

    /**
     * Валюта не ясна (в тексте документа разные валюты или суммы в тысячах): цены сами не подставляются, курс — 400. И
     * курс, записанный такому файлу прежним кодом (до 2026-10-18 он принимал курс к любой «чужой» валюте, в том числе
     * MIXED), цены не подставляет: из «валюты не ясна» пересчитывать нечего.
     */
    @Test
    void mixedCurrency_notApplied_rateRejected() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.docx", table(MoneyText.MIXED,
                "Скальпели офтальмологические: Скальпель MSL24", "4700"))), null);
        assertThat(o.applied()).isZero();
        assertThat(o.foreignCurrency()).isEqualTo(MoneyText.MIXED);
        assertThat(item(0).getResponsePrice()).isNull();
        assertThatThrownBy(() -> service.setRate(pr.getId(), o.fileIds().get(0), new BigDecimal("6")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("не ясна");

        SupplierReplyFile f = files.findById(o.fileIds().get(0)).orElseThrow();
        f.setRate(new BigDecimal("6"));
        f.setRateCurrency(MoneyText.MIXED);
        em.flush();
        assertThat(service.applyFile(pr.getId(), f.getId()).applied()).isZero();
        assertThat(item(0).getResponsePrice()).isNull();
    }

    /** Такой курс («валюта не ясна», записанный прежним кодом) новый файл того же запроса не наследует. */
    @Test
    void mixedRateNotInherited() {
        ReplyOutcome first = service.ingest(pr, List.of(parsed("kp1.docx", table(MoneyText.MIXED,
                "Скальпели офтальмологические: Скальпель MSL24", "4700"))), null);
        SupplierReplyFile f = files.findById(first.fileIds().get(0)).orElseThrow();
        f.setRate(new BigDecimal("6"));
        f.setRateCurrency(MoneyText.MIXED);
        f.setRateSetAt(java.time.OffsetDateTime.now());
        em.flush();
        ReplyOutcome second = service.ingest(pr, List.of(parsed("kp2.docx", table(MoneyText.MIXED,
                "Скальпели офтальмологические: Скальпель MSL24", "4800"))), null);
        assertThat(files.findById(second.fileIds().get(0)).orElseThrow().getRate()).isNull();
        assertThat(second.applied()).isZero();
        assertThat(item(0).getResponsePrice()).isNull();
    }

    // ---- Валюта цен файла, выбранная на экране сверки (2026-10-18) -------------------------------------------------

    /** Строки как у kzTable, но валюту знает не таблица: валюта и её источник заданы отдельно. */
    static ReplyTable kzRows(String currency, ReplyCurrencySource kind, String... texts) {
        ReplyTable t = table(null, "Скальпели офтальмологические: Скальпель MSL24", "4 700",
                "Скальпели офтальмологические: Скальпель MST15", "4 200",
                "Материал шовный стерильный нейлон 2056", "11 750", "Доставка", "5 000");
        return t.withCurrency(currency, new ReplyTable.CurrencyFrom(kind, List.of(texts), null, null));
    }

    SupplierReplyFile file(ReplyOutcome o) {
        return files.findById(o.fileIds().get(0)).orElseThrow();
    }

    /** Рубли нашлись в тексте (догадка), а цены в тенге: выбрали «тенге» — цены подставлены, источник — вручную, кто и когда. */
    @Test
    void setCurrency_textToMarket_pricesApplied() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.docx", kzRows("RUB", ReplyCurrencySource.TEXT, "…на сумму 18 450,00 руб.…"))), null);
        assertThat(o.applied()).isZero();

        SupplierReplyService.RateResult r = service.setCurrency(pr.getId(), o.fileIds().get(0), "KZT", "vlad");

        assertThat(r.applied()).isEqualTo(3);
        assertThat(r.removed()).isZero();
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
        assertThat(item(0).isResponseAuto()).isTrue();
        ReplyTable t = file(o).getParsed();
        assertThat(t.currency()).isEqualTo("KZT");
        assertThat(t.currencySource()).isEqualTo(ReplyCurrencySource.MANUAL);
        assertThat(t.currencyFrom().by()).isEqualTo("vlad");
        assertThat(t.currencyFrom().at()).isNotBlank();
        assertThat(t.rows()).hasSize(4);   // таблица та же — меняется только валюта
        assertThat(requests.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("RESPONDED");
    }

    /**
     * Валюта не указана (считали тенге — цены подставлены), а цены в рублях: выбрали «рубли» — несверенные цены этого
     * файла сняты, дальше — путь курса: «Пересчитать» подставляет цены × курс.
     */
    @Test
    void setCurrency_noneToForeign_pricesRemoved_thenRatePath() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzRows(null, ReplyCurrencySource.NONE))), null);
        assertThat(o.applied()).isEqualTo(3);

        SupplierReplyService.RateResult r = service.setCurrency(pr.getId(), o.fileIds().get(0), "RUB", "vlad");

        assertThat(r.applied()).isZero();
        assertThat(r.removed()).isEqualTo(3);
        assertThat(item(0).getResponsePrice()).isNull();
        assertThat(item(0).getResponseFileId()).isNull();
        assertThat(service.setRate(pr.getId(), o.fileIds().get(0), new BigDecimal("6")).applied()).isEqualTo(3);
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("28200");
    }

    /** В документе разные валюты: цены не подставлялись; выбрали «тенге» — подставлены. */
    @Test
    void setCurrency_mixedToMarket_pricesApplied() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.docx", kzRows(MoneyText.MIXED, ReplyCurrencySource.MIXED, "«в рублях»", "«в тенге»"))), null);
        assertThat(o.applied()).isZero();
        assertThat(service.setCurrency(pr.getId(), o.fileIds().get(0), "KZT", "vlad").applied()).isEqualTo(3);
        assertThat(item(2).getResponsePrice()).isEqualByComparingTo("11750");
    }

    /** Ручная цена при смене валюты не трогается — и считается в «ручные не тронуты». */
    @Test
    void setCurrency_manualPriceKept() {
        PriceRequestItem manual = pr.getItems().get(1);
        manual.setResponsePrice(new BigDecimal("999"));
        em.flush();
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzRows(null, ReplyCurrencySource.NONE))), null);
        assertThat(o.applied()).isEqualTo(2);

        SupplierReplyService.RateResult r = service.setCurrency(pr.getId(), o.fileIds().get(0), "KZT", "vlad");

        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.keptManual()).isEqualTo(1);
        assertThat(r.removed()).isZero();
        assertThat(item(1).getResponsePrice()).isEqualByComparingTo("999");
        assertThat(item(1).isResponseAuto()).isFalse();
    }

    /** Закрытый запрос: выбор запоминается, цены не трогаются (как курс у закрытого). */
    @Test
    void setCurrency_closedRequest_rememberedPricesKept() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzRows(null, ReplyCurrencySource.NONE))), null);
        PriceRequest closed = requests.findById(pr.getId()).orElseThrow();
        closed.setStatus("CLOSED");
        em.flush();

        SupplierReplyService.RateResult r = service.setCurrency(pr.getId(), o.fileIds().get(0), "RUB", "vlad");

        assertThat(r.closed()).isTrue();
        assertThat(r.applied()).isZero();
        assertThat(file(o).getParsed().currency()).isEqualTo("RUB");
        assertThat(file(o).getParsed().currencySource()).isEqualTo(ReplyCurrencySource.MANUAL);
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
        assertThat(item(0).isResponseAuto()).isTrue();
    }

    /** Выбрать можно только тенге, рубли, доллары, евро, юани (регистр не важен); «у.е.», MIXED, чужое и пустое — 400. */
    @Test
    void setCurrency_unknownIs400() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzRows(null, ReplyCurrencySource.NONE))), null);
        Long f = o.fileIds().get(0);
        for (String bad : new String[]{"UE", MoneyText.MIXED, "GBP", "", null}) {
            assertThatThrownBy(() -> service.setCurrency(pr.getId(), f, bad, "vlad")).as(String.valueOf(bad))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("Неизвестная валюта");
        }
        assertThat(file(o).getParsed().currencySource()).isEqualTo(ReplyCurrencySource.NONE);
        assertThat(service.setCurrency(pr.getId(), f, " kzt ", "vlad").closed()).isFalse();
        assertThat(file(o).getParsed().currency()).isEqualTo("KZT");
    }

    /** Курс файла, вписанный для другой валюты, при смене валюты снимается; для той же — остаётся, цены по нему. */
    @Test
    void setCurrency_rateOfOtherCurrencyReset_sameKept() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.docx", kzRows("RUB", ReplyCurrencySource.TEXT, "…500 руб.…"))), null);
        Long f = o.fileIds().get(0);
        assertThat(service.setRate(pr.getId(), f, new BigDecimal("6")).applied()).isEqualTo(3);

        SupplierReplyService.RateResult same = service.setCurrency(pr.getId(), f, "RUB", "vlad");
        assertThat(same.applied()).isEqualTo(3);
        assertThat(same.removed()).isZero();
        assertThat(file(o).getRate()).isEqualByComparingTo("6");
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("28200");

        SupplierReplyService.RateResult other = service.setCurrency(pr.getId(), f, "USD", "vlad");
        assertThat(other.applied()).isZero();
        assertThat(other.removed()).isEqualTo(3);
        assertThat(file(o).getRate()).isNull();
        assertThat(file(o).getRateCurrency()).isNull();
        assertThat(item(0).getResponsePrice()).isNull();
    }

    @Test
    void closedRequestStoresFilesButNoPrices() {
        pr.setStatus("CLOSED");
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);
        assertThat(o.fileIds()).hasSize(1);
        assertThat(o.applied()).isZero();
        assertThat(o.closed()).isTrue();
        assertThat(item(0).getResponsePrice()).isNull();
    }

    @Test
    void pdfIsViewOnly() throws Exception {
        ReplyOutcome o = service.ingest(pr, List.of(pdf("kp.pdf")), null);
        assertThat(o.viewOnly()).isTrue();
        assertThat(o.parsedName()).isNull();
        assertThat(o.applied()).isZero();
    }

    @Test
    void markResponded_fromSentAndDeclined_notFromClosed() {
        SupplierReplyService.markResponded(pr, "Добрый день, КП во вложении");
        assertThat(pr.getStatus()).isEqualTo("RESPONDED");
        assertThat(pr.getResponseDate()).isNotNull();
        assertThat(pr.getNote()).isEqualTo("Добрый день, КП во вложении");
        pr.setStatus("CLOSED");
        SupplierReplyService.markResponded(pr, "ещё");
        assertThat(pr.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void setColumnsReappliesAndLearns() {
        ReplyTable t = kzTable();
        ReplyTable wrong = t.withRoles(java.util.Map.of(2, LineField.IGNORE));
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", wrong)), null);
        assertThat(o.applied()).isZero();
        Long fileId = o.fileIds().get(0);
        String header = "zz цена своя " + System.nanoTime();
        SupplierReplyFile f = files.findById(fileId).orElseThrow();
        f.setParsed(new ReplyTable(wrong.source(), wrong.headerRow(), wrong.score(), "KZT", List.of(
                wrong.columns().get(0), wrong.columns().get(1), new ReplyTable.Column(2, header, LineField.IGNORE)), wrong.rows()));
        em.flush();

        MarketContext.set(Market.KZ);
        service.setColumns(pr.getId(), fileId, List.of(new ReplyColumnRole(2, LineField.PRICE)));

        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
        assertThat(synonyms.findByHeaderNorm(header)).get().extracting(HeaderSynonym::getField).isEqualTo(LineField.PRICE);
    }

    @Test
    void deleteUploadedFileRevertsUnverifiedPrices_emailFileIs400() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, тг"),
                List.of("Скальпели офтальмологические: Скальпель MSL24", "2", "4 700")));
        SupplierReplyFile up = service.upload(pr.getId(), "мой.xlsx", "application/octet-stream", xlsx, "admin");
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");

        service.deleteFile(pr.getId(), up.getId());
        assertThat(item(0).getResponsePrice()).isNull();
        assertThat(item(0).getResponseFileId()).isNull();

        ReplyOutcome mail = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);
        assertThatThrownBy(() -> service.deleteFile(pr.getId(), mail.fileIds().get(0))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void verifyClearsAuto_otherMarketIs404() {
        service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);
        assertThat(service.verify(pr.getId())).isEqualTo(3);
        assertThat(item(0).isResponseAuto()).isFalse();

        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.verify(pr.getId())).isInstanceOf(NotFoundException.class);
    }

    /** Строка в чужой валюте в таблице рынка не подставляется — валюта у строки (колонка «Валюта»), ревью ветки. */
    @Test
    void foreignCurrencyRowInMarketTableNotApplied() {
        ReplyTable t = new ReplyTable("Лист1", 4, 4, "KZT", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена", LineField.PRICE), new ReplyTable.Column(3, "Валюта", LineField.CURRENCY)),
                List.of(new ReplyTable.Row(5, List.of("Скальпели офтальмологические: Скальпель MSL24", "1", "4 700", "тенге")),
                        new ReplyTable.Row(6, List.of("Скальпели офтальмологические: Скальпель MST15", "1", "4 200", "тенге")),
                        new ReplyTable.Row(7, List.of("Материал шовный стерильный нейлон 2056", "1", "25", "EUR"))));

        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", t)), null);

        assertThat(o.applied()).isEqualTo(2);
        assertThat(item(2).getResponsePrice()).isNull();
    }

    /** У закрытого (принятого) запроса правка ролей колонок цены не трогает — спека §3.4 (ревью ветки). */
    @Test
    void setColumnsOnClosedRequestKeepsPrices() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);
        pr.setStatus("ACCEPTED");
        em.flush();

        service.setColumns(pr.getId(), o.fileIds().get(0), List.of(new ReplyColumnRole(2, LineField.IGNORE)));

        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
        assertThat(item(0).isResponseAuto()).isTrue();
    }

    /**
     * Курс (2026-10-16, КП ФОТЕК в рублях на запрос West-Med): цены файла × курс → цена в валюте запроса, до тиына
     * (HALF_UP), с 💡 и исходной ценой и курсом в примечании; ручная цена не трогается; снять курс — пересчитанные
     * цены уходят.
     */
    @Test
    void rateConvertsForeignPrices_clearingRemovesThem() {
        PriceRequestItem manual = pr.getItems().get(1);
        manual.setResponsePrice(new BigDecimal("999"));
        em.flush();
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xls", table("RUB",
                "Скальпели офтальмологические: Скальпель MSL24", "4 700",
                "Скальпели офтальмологические: Скальпель MST15", "4 200",
                "Материал шовный стерильный нейлон 2056", "11 750"))), null);
        assertThat(o.applied()).isZero();
        Long fileId = o.fileIds().get(0);

        service.setRate(pr.getId(), fileId, new BigDecimal("5.85555"));

        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("27521.09");   // 4 700 × 5,85555 = 27 521,085
        assertThat(item(0).isResponseAuto()).isTrue();
        assertThat(item(0).getResponseFileRow()).isEqualTo(5);
        assertThat(item(0).getResponseNote().replace(' ', ' ')).contains("4 700,00 руб. × курс 5,85555 = 27 521,09 тг");
        assertThat(item(2).getResponsePrice()).isEqualByComparingTo("68802.71");   // 11 750 × 5,85555 = 68 802,7125
        assertThat(item(1).getResponsePrice()).isEqualByComparingTo("999");
        assertThat(item(1).isResponseAuto()).isFalse();
        assertThat(requests.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("RESPONDED");
        SupplierReplyFile f = files.findById(fileId).orElseThrow();
        assertThat(f.getRate()).isEqualByComparingTo("5.85555");
        assertThat(f.getRateCurrency()).isEqualTo("RUB");

        service.setRate(pr.getId(), fileId, null);

        assertThat(item(0).getResponsePrice()).isNull();
        assertThat(item(0).isResponseAuto()).isFalse();
        assertThat(item(1).getResponsePrice()).isEqualByComparingTo("999");
        assertThat(files.findById(fileId).orElseThrow().getRate()).isNull();
    }

    @Test
    void rateValidation_marketCurrencyFile_otherMarket() {
        Long rub = service.ingest(pr, List.of(parsed("kp.xls", table("RUB", "Скальпели офтальмологические: Скальпель MSL24", "4 700"))), null)
                .fileIds().get(0);
        for (String bad : List.of("0", "-6", "100001", "6.1234567")) {
            assertThatThrownBy(() -> service.setRate(pr.getId(), rub, new BigDecimal(bad))).as(bad).isInstanceOf(BadRequestException.class);
        }
        Long kzt = service.ingest(pr, List.of(parsed("kp2.xlsx", kzTable())), null).fileIds().get(0);
        assertThatThrownBy(() -> service.setRate(pr.getId(), kzt, new BigDecimal("6")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("курс не нужен");
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.setRate(pr.getId(), rub, new BigDecimal("6"))).isInstanceOf(NotFoundException.class);
    }

    /** У закрытого запроса курс запоминается, а цены не трогаются (как роли колонок, спека §3.4). */
    @Test
    void rateOnClosedRequestStored_pricesUntouched() {
        Long fileId = service.ingest(pr, List.of(parsed("kp.xls", table("RUB", "Скальпели офтальмологические: Скальпель MSL24", "4 700"))), null)
                .fileIds().get(0);
        pr.setStatus("ACCEPTED");
        em.flush();

        service.setRate(pr.getId(), fileId, new BigDecimal("6"));

        assertThat(item(0).getResponsePrice()).isNull();
        assertThat(files.findById(fileId).orElseThrow().getRate()).isEqualByComparingTo("6");
    }

    /** Курс — для валюты файла: строка в третьей валюте (колонка «Валюта») не пересчитывается. */
    @Test
    void rateConvertsOnlyItsCurrency() {
        ReplyTable t = new ReplyTable("Лист1", 4, 4, "RUB", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена", LineField.PRICE), new ReplyTable.Column(3, "Валюта", LineField.CURRENCY)),
                List.of(new ReplyTable.Row(5, List.of("Скальпели офтальмологические: Скальпель MSL24", "1", "4 700", "руб.")),
                        new ReplyTable.Row(6, List.of("Скальпели офтальмологические: Скальпель MST15", "1", "4 200", "руб.")),
                        new ReplyTable.Row(7, List.of("Материал шовный стерильный нейлон 2056", "1", "25", "EUR"))));
        Long fileId = service.ingest(pr, List.of(parsed("kp.xls", t)), null).fileIds().get(0);

        service.setRate(pr.getId(), fileId, new BigDecimal("6"));

        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("28200");
        assertThat(item(2).getResponsePrice()).isNull();
    }

    static ReplyTable rubTable() {
        return table("RUB", "Скальпели офтальмологические: Скальпель MSL24", "4 700",
                "Скальпели офтальмологические: Скальпель MST15", "4 200",
                "Материал шовный стерильный нейлон 2056", "11 750");
    }

    /**
     * Ревью ветки курса: смена курса после «Всё верно» и после выбора строки — цены, которые никто не правил (равны
     * пересчёту по прежнему курсу), идут за новым курсом со своей строкой и снова 💡; правленная руками — не трогается.
     */
    @Test
    void rateChange_followsVerifiedAndPickedPrices_keepsHandEdited() {
        Long fileId = service.ingest(pr, List.of(parsed("kp.xls", rubTable())), null).fileIds().get(0);
        service.setRate(pr.getId(), fileId, new BigDecimal("6"));
        service.verify(pr.getId());                                          // «Всё верно»
        PriceRequestItem picked = item(1);                                    // «Другая строка ▾»: строка 5 вместо 6
        picked.setResponsePrice(new BigDecimal("28200"));
        picked.setResponseFileRow(5);
        item(2).setResponsePrice(new BigDecimal("70000"));                    // 70 500 поправили руками
        em.flush();

        SupplierReplyService.RateResult r = service.setRate(pr.getId(), fileId, new BigDecimal("5.8"));

        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("27260");    // 4 700 × 5,8
        assertThat(item(0).isResponseAuto()).isTrue();
        assertThat(item(0).getResponseNote()).contains("× курс 5,8");
        assertThat(item(1).getResponsePrice()).isEqualByComparingTo("27260");    // своя выбранная строка, не строка сопоставления
        assertThat(item(1).getResponseFileRow()).isEqualTo(5);
        assertThat(item(1).isResponseAuto()).isTrue();
        assertThat(item(2).getResponsePrice()).isEqualByComparingTo("70000");
        assertThat(item(2).isResponseAuto()).isFalse();
        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.keptManual()).isEqualTo(1);
        assertThat(r.closed()).isFalse();
    }

    /** Снять курс — уходят и сверенные цены по нему; правленная руками остаётся. */
    @Test
    void clearingRate_removesVerifiedPricesToo_keepsHandEdited() {
        Long fileId = service.ingest(pr, List.of(parsed("kp.xls", rubTable())), null).fileIds().get(0);
        service.setRate(pr.getId(), fileId, new BigDecimal("6"));
        service.verify(pr.getId());
        item(2).setResponsePrice(new BigDecimal("70000"));
        em.flush();

        SupplierReplyService.RateResult r = service.setRate(pr.getId(), fileId, null);

        assertThat(item(0).getResponsePrice()).isNull();
        assertThat(item(0).getResponseFileId()).isNull();
        assertThat(item(1).getResponsePrice()).isNull();
        assertThat(item(2).getResponsePrice()).isEqualByComparingTo("70000");
        assertThat(r.removed()).isEqualTo(2);
        assertThat(r.keptManual()).isEqualTo(1);
    }

    /** Тот же курс ещё раз («Пересчитать заново») сверенные цены не возвращает в 💡. */
    @Test
    void sameRateAgain_keepsVerifiedState() {
        Long fileId = service.ingest(pr, List.of(parsed("kp.xls", rubTable())), null).fileIds().get(0);
        service.setRate(pr.getId(), fileId, new BigDecimal("6"));
        service.verify(pr.getId());

        service.setRate(pr.getId(), fileId, new BigDecimal("6.000"));

        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("28200");
        assertThat(item(0).isResponseAuto()).isFalse();
    }

    /** Рынок РФ, КП в тенге: в примечании «… = 940,00 руб.» — без второй точки после «руб.». */
    @Test
    void rfMarketNote_noDoubleDot() {
        PriceRequest rf = request(Market.RF);
        Long fileId = service.ingest(rf, List.of(parsed("kp.xlsx", kzTable())), null).fileIds().get(0);

        service.setRate(rf.getId(), fileId, new BigDecimal("0.2"));

        PriceRequestItem it = items.findById(rf.getItems().get(0).getId()).orElseThrow();
        assertThat(it.getResponsePrice()).isEqualByComparingTo("940");   // 4 700 тг × 0,2
        assertThat(it.getResponseNote()).endsWith("940,00 руб.").doesNotContain("..");
    }

    /** У закрытого запроса — итог «закрыт»: экран не скажет «пересчитано». */
    @Test
    void rateOnClosedRequest_resultSaysClosed() {
        Long fileId = service.ingest(pr, List.of(parsed("kp.xls", rubTable())), null).fileIds().get(0);
        pr.setStatus("CLOSED");
        em.flush();

        SupplierReplyService.RateResult r = service.setRate(pr.getId(), fileId, new BigDecimal("6"));

        assertThat(r.closed()).isTrue();
        assertThat(r.applied()).isZero();
    }

    /**
     * Исправленное КП в той же валюте (второе письмо, загрузка вручную) берёт курс, уже вписанный в этом запросе:
     * цены подставляются сразу, по курсу — он в итоге (строка уведомления).
     */
    @Test
    void newFileInSameCurrency_takesRateOfRequest() throws Exception {
        Long first = service.ingest(pr, List.of(parsed("kp.xls", rubTable())), null).fileIds().get(0);
        service.setRate(pr.getId(), first, new BigDecimal("6"));

        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp2.xls", table("RUB",
                "Скальпели офтальмологические: Скальпель MSL24", "4 500"))), null);

        assertThat(o.applied()).isEqualTo(1);
        assertThat(o.rate()).isEqualByComparingTo("6");
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("27000");   // 4 500 × 6
        assertThat(item(0).getResponseFileId()).isEqualTo(o.fileIds().get(0));
        assertThat(files.findById(o.fileIds().get(0)).orElseThrow().getRate()).isEqualByComparingTo("6");

        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, руб."),
                List.of("Скальпели офтальмологические: Скальпель MST15", "2", "4 100")));
        SupplierReplyFile up = service.upload(pr.getId(), "kp3.xlsx", "application/octet-stream", xlsx, "admin");
        assertThat(up.getRate()).isEqualByComparingTo("6");
        assertThat(item(1).getResponsePrice()).isEqualByComparingTo("24600");   // 4 100 × 6
    }

    /** Словарь учится только на колонке, которую поправил оператор, — не на догадках по остальным (ревью ветки). */
    @Test
    void setColumnsLearnsOnlyChangedColumns() {
        String untouched = "zz наименование своё " + System.nanoTime();
        String changed = "zz цена своя " + System.nanoTime();
        ReplyTable t = new ReplyTable("Лист1", 4, 3, "KZT", List.of(
                new ReplyTable.Column(0, untouched, LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, changed, LineField.IGNORE)), kzTable().rows());
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", t)), null);

        service.setColumns(pr.getId(), o.fileIds().get(0), List.of(new ReplyColumnRole(0, LineField.NAME),
                new ReplyColumnRole(1, LineField.QUANTITY), new ReplyColumnRole(2, LineField.PRICE)));

        assertThat(synonyms.findByHeaderNorm(changed)).get().extracting(HeaderSynonym::getField).isEqualTo(LineField.PRICE);
        assertThat(synonyms.findByHeaderNorm(untouched)).isEmpty();
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
    }
}
