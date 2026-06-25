package com.vladoose.nir.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InboundImportReuseTest {

    @Autowired InboundEmailRepository repository;
    @Autowired PrivateRequestImportService importService;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void preview_runsExtractorOnStoredAttachment_andMarkProcessed() throws Exception {
        MarketContext.set(Market.KZ);
        InboundEmail e = repository.save(InboundEmail.builder()
                .fromAddress("c@x.kz").subject("Заявка")
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .type(InboundType.CLIENT_REQUEST).status(InboundStatus.NEW)
                .attachmentName("z.xlsx").attachment(xlsx()).build());
        repository.flush();

        ImportPreviewResponse p = importService.preview(e.getAttachment(), e.getAttachmentName());
        assertThat(p.getColumns()).extracting("field").contains(LineField.NAME);

        e.setStatus(InboundStatus.PROCESSED);
        repository.save(e);
        assertThat(repository.findById(e.getId()).orElseThrow().getStatus())
                .isEqualTo(InboundStatus.PROCESSED);
    }

    private byte[] xlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet();
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Наименование");
            h.createCell(1).setCellValue("Производитель");
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("Монитор пациента");
            r.createCell(1).setCellValue("Philips");
            wb.write(out);
            return out.toByteArray();
        }
    }
}
