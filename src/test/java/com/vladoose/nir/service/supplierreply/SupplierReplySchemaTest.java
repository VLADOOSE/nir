package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestImportService;
import jakarta.persistence.EntityManager;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V30: файл ответа хранит таблицу (JSONB), один файл — один раз на запрос, каскады, перенос 💡 из примечания. */
@SpringBootTest
@Transactional
class SupplierReplySchemaTest {

    @Autowired SupplierReplyFileRepository files;
    @Autowired PriceRequestRepository requests;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired PrivateRequestImportService importService;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    PriceRequest pr() {
        Tender t = Tender.builder().tenderNumber("ZZSRF-" + System.nanoTime()).status("NEW").source(Source.PRIVATE_REQUEST).build();
        t.getLots().add(TenderLot.builder().tender(t).equipName("ZZ Аппарат").quantity(1).build());
        t = tenders.save(t);
        Distributor d = distributors.save(Distributor.builder().name("ZZSRF Дистр " + System.nanoTime()).build());
        PriceRequest p = PriceRequest.builder().tender(t).distributor(d).status("SENT").build();
        p.getItems().add(PriceRequestItem.builder().priceRequest(p).tenderLot(t.getLots().get(0)).requestedQuantity(1).build());
        return requests.save(p);
    }

    static ReplyTable table() {
        return new ReplyTable("Лист1", 3, 2, "KZT",
                List.of(new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Цена, тг", LineField.PRICE)),
                List.of(new ReplyTable.Row(4, List.of("ZZ Аппарат", "100"))));
    }

    SupplierReplyFile file(Long prId, String sha) {
        return files.save(SupplierReplyFile.builder().priceRequestId(prId).fileName("kp.xlsx").sha256(sha)
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).parsed(table()).data(new byte[]{1, 2}).build());
    }

    @Test
    void parsedTableRoundTrips() {
        PriceRequest p = pr();
        Long id = file(p.getId(), "a1").getId();
        em.flush();
        em.clear();

        SupplierReplyFile back = files.findById(id).orElseThrow();
        assertThat(back.getParsed()).isEqualTo(table());
        assertThat(back.getCreatedAt()).isNotNull();
        assertThat(files.findParsed(id)).contains(table());
        assertThat(files.findInfo(p.getId())).singleElement().extracting(i -> i.fileName()).isEqualTo("kp.xlsx");
    }

    @Test
    void sameFileTwiceInOneRequestRejected() {
        PriceRequest p = pr();
        file(p.getId(), "same");
        em.flush();
        assertThatThrownBy(() -> {
            file(p.getId(), "same");
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingFileUnlinksItem_deletingRequestDeletesFiles() {
        PriceRequest p = pr();
        SupplierReplyFile f = file(p.getId(), "b1");
        PriceRequestItem it = p.getItems().get(0);
        it.setResponseFileId(f.getId());
        it.setResponseFileRow(4);
        em.flush();

        em.createNativeQuery("DELETE FROM supplier_reply_file WHERE id = :id").setParameter("id", f.getId()).executeUpdate();
        em.clear();
        Object link = em.createNativeQuery("SELECT response_file_id FROM price_request_item WHERE id = :id")
                .setParameter("id", it.getId()).getSingleResult();
        assertThat(link).isNull();

        PriceRequest p2 = pr();
        file(p2.getId(), "c1");
        em.flush();
        em.createNativeQuery("DELETE FROM price_request_item WHERE price_request_id = :id").setParameter("id", p2.getId()).executeUpdate();
        em.createNativeQuery("DELETE FROM price_request WHERE id = :id").setParameter("id", p2.getId()).executeUpdate();
        Number left = (Number) em.createNativeQuery("SELECT count(*) FROM supplier_reply_file WHERE price_request_id = :id")
                .setParameter("id", p2.getId()).getSingleResult();
        assertThat(left.intValue()).isZero();
    }

    /** То же выражение, что в V30: пометка «💡 …» уходит из примечания, «Срок…» и «Контекст…» остаются. */
    @Test
    void autoMarkMigrationExpression() {
        String sql = "SELECT NULLIF(regexp_replace(CAST(:n AS text), '^💡[^.]*\\.?\\s*', ''), '')";
        assertThat(em.createNativeQuery(sql).setParameter("n", "💡 Цена распознана автоматически, проверьте. Срок: 3 недели.")
                .getSingleResult()).isEqualTo("Срок: 3 недели.");
        assertThat(em.createNativeQuery(sql).setParameter("n", "💡 Цена распознана автоматически, проверьте.")
                .getSingleResult()).isNull();
        Number marked = (Number) em.createNativeQuery("SELECT count(*) FROM price_request_item WHERE response_note LIKE '💡%'")
                .getSingleResult();
        assertThat(marked.intValue()).isZero();
    }

    /** Сид V30 знает «цена» → цена; смета клиники показывает такую колонку «Игнорировать», как до V30. */
    @Test
    void clinicPreviewShowsSupplierRolesAsIgnore() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet();
            var head = sheet.createRow(0);
            head.createCell(0).setCellValue("Наименование");
            head.createCell(1).setCellValue("Цена");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("Кушетка");
            data.createCell(1).setCellValue(95000);
            wb.write(out);
            xlsx = out.toByteArray();
        }
        assertThat(importService.preview(xlsx, "смета.xlsx").getColumns().get(1).getField()).isEqualTo(LineField.IGNORE);
    }
}
