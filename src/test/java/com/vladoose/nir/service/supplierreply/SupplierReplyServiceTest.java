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
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        Tender t = Tender.builder().tenderNumber("ZZSVC-" + System.nanoTime()).status("NEW").source(Source.PRIVATE_REQUEST).build();
        for (String n : List.of("Скальпель офтальмологический MSL24", "Скальпель офтальмологический MST15", "Материал шовный нейлон 2056")) {
            t.getLots().add(TenderLot.builder().tender(t).equipName(n).quantity(2).build());
        }
        t = tenders.save(t);
        Distributor d = distributors.save(Distributor.builder().name("ZZSVC Дистр " + System.nanoTime()).build());
        pr = PriceRequest.builder().tender(t).distributor(d).status("SENT").build();
        for (TenderLot l : t.getLots()) pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(2).build());
        pr = requests.save(pr);
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
