package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** Документ запроса из PriceRequest (спека §6.4): против утечки, обрезка ТЗ, имя файла. */
@SpringBootTest
@Transactional
class RfqDocumentServiceTest {

    @Autowired PrivateRequestService service;
    @Autowired RfqDocumentService documents;
    @Autowired DistributorRepository distributorRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist() {
        return distributorRepository.save(Distributor.builder().name("ZZ Документ " + System.nanoTime()).email("d@doc.kz").build());
    }

    private static PriceRequest draft(Tender t, TenderLot lot, Distributor d, boolean disclose) {
        return PriceRequest.builder().tender(t).distributor(d).market(Market.KZ).discloseClient(disclose).withDocument(true)
                .items(new ArrayList<>(List.of(PriceRequestItem.builder().tenderLot(lot).requestedQuantity(1).build()))).build();
    }

    private static String text(byte[] docx) throws Exception {
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(docx)); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
            return x.getText().replace(' ', ' ');
        }
    }

    @Test
    void clientOnlyWithFlag_requestNumberAndContactNever() throws Exception {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Аппарат DOC", "ZZDOCBRAND", 1, null, null, null)), LinesMode.APPEND);
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Клиника Документ");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Контакт Документ");
        service.updateField(t.getId(), PrivateRequestField.DELIVERY_ADDRESS, "г. Уральск");
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist();

        String hidden = text(documents.render(draft(t, lot, d, false)).bytes());
        assertThat(hidden).contains("ZZ Аппарат DOC (ZZDOCBRAND)").contains(d.getName())
                .doesNotContain("ZZ Клиника Документ").doesNotContain("ZZ Контакт Документ").doesNotContain(t.getTenderNumber());

        String shown = text(documents.render(draft(t, lot, d, true)).bytes());
        assertThat(shown).contains("Конечный заказчик: ZZ Клиника Документ, место поставки: г. Уральск")
                .doesNotContain("ZZ Контакт Документ").doesNotContain(t.getTenderNumber());
    }

    @Test
    void tenderNumberAndCustomerNeverInDocument() throws Exception {
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-17295275-1").status("ACTIVE")
                .source(Source.PUBLIC_TENDER).customerName("ZZ ГКП Областная больница").build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1).equipName("ZZ Монитор пациента")
                .quantity(3).requiredSpec("Экран не менее 12 дюймов").build());

        // в тендерах «Указать клиента» нет: канал ставит discloseClient = false всегда (спека §6.6)
        String text = text(documents.render(draft(t, lot, dist(), false)).bytes());

        assertThat(text).contains("ZZ Монитор пациента").contains("Экран не менее 12 дюймов")
                .doesNotContain("ZZ-17295275-1").doesNotContain("ZZ ГКП Областная больница");
    }

    @Test
    void longSpecIsTrimmedLikeTheLetter() throws Exception {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Длинное ТЗ", null, 1, null, "Характеристика ".repeat(400), null)),
                LinesMode.APPEND);

        String text = text(documents.render(draft(t, t.getLots().get(0), dist(), false)).bytes());

        assertThat(text).contains("(полное ТЗ — по запросу)");
        assertThat(text.length()).isLessThan(4000);
    }

    @Test
    void fileNameHasNumberDateAndCompanyWithoutQuotes() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Файл", null, 1, null, null, null)), LinesMode.APPEND);

        String name = documents.render(draft(t, t.getLots().get(0), dist(), false)).fileName();

        assertThat(name).matches("Запрос КП № — от \\d{2}\\.\\d{2}\\.\\d{4} — [^\"«»]+\\.docx");
    }
}
