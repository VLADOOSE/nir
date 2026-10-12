package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.EmailTemplateRepository;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    @Autowired EmailTemplateRepository templateRepository;

    // шаблон письма рынка правит оператор на «Шаблоне письма КП» (nirdb постоянная): тесты ждут зашитый — без него
    // своя строка о заказчике или тело без {{дедлайн}} уронили бы гейт без регресса (CLAUDE.md §14); откат — транзакцией теста
    @BeforeEach void kz() {
        MarketContext.set(Market.KZ);
        templateRepository.deleteAllInBatch();
    }
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
            return x.getText().replace('\u00A0', ' ');
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

    /** Сырой ТЗ goszakup (как в LotDescriptiveTextTest): номер закупки, номер лота площадки и КАТО места поставки. */
    private static final String GOSZAKUP_TZ = String.join("\n",
            "Приложение 2", "к конкурсной документации", "Номер закупки: № 17279420-1", "Наименование", "закупки:",
            "Резиновые пластинки для аппарата электрофореза \"Элэскулап\"", "Номер лота: № 86887302-ЗЦП3",
            "Наименование лота: Электрод", "Описание лота: платиновый", "Дополнительное", "описание лота:",
            "электроды силиконовые 55*80 мм", "Количество: 10", "Единица измерения: Комплект",
            "Места поставки: 231010000, Атырауская область, г.Атырау",
            "Срок поставки: в течение 2026 года (по заявке Заказчика)");

    private static String skSpec(String fixture) throws Exception {
        try (InputStream in = RfqDocumentServiceTest.class.getResourceAsStream("/skpharmacy/specs/" + fixture + ".txt")) {
            assertThat(in).as("фикстура %s", fixture).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Против утечки (спека §10): ТЗ лота площадки уходит в документ очищенным, как в письме. Сырой goszakup-ТЗ несёт номер
     * закупки, номер лота и место поставки, ТЗ СК-Фармации — код лота площадки в шапке (адресует объявление); в документе
     * их нет, а характеристики есть. Держит {@code LotDescriptiveText.requirementsForEmail} в {@code RfqDocumentService}:
     * без него документ, приложенный в панели КП тендера, раскрыл бы тендер поставщику.
     */
    @Test
    void platformSpecIsSanitizedLikeTheLetter() throws Exception {
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-DOC-SPEC").status("ACTIVE")
                .source(Source.PUBLIC_TENDER).build());
        TenderLot goszakup = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1).equipName("ZZ Электрод")
                .quantity(10).requiredSpec(GOSZAKUP_TZ).build());
        TenderLot sk = lotRepository.save(TenderLot.builder().tender(t).lotNumber(2).equipName("ZZ Аппарат УЗИ")
                .quantity(1).requiredSpec(skSpec("lot-4875083-t1")).build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(dist()).market(Market.KZ).withDocument(true)
                .items(new ArrayList<>(List.of(
                        PriceRequestItem.builder().tenderLot(goszakup).requestedQuantity(10).build(),
                        PriceRequestItem.builder().tenderLot(sk).requestedQuantity(1).build()))).build();

        String text = text(documents.render(pr).bytes());

        assertThat(text).contains("электроды силиконовые 55*80 мм");
        assertThat(text).doesNotContain("17279420").doesNotContain("86887302").doesNotContain("231010000")
                .doesNotContain("Атырауская");
        assertThat(text).doesNotContain("4875083").doesNotContain("522144").doesNotContain("Техническая спецификация");
    }

    @Test
    void longSpecIsTrimmedLikeTheLetter() throws Exception {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Длинное ТЗ", null, 1, null, "Характеристика ".repeat(400), null)),
                LinesMode.APPEND);

        String text = text(documents.render(draft(t, t.getLots().get(0), dist(), false)).bytes());

        assertThat(text).contains("(полное ТЗ \u2013 по запросу)");
        assertThat(text.length()).isLessThan(4000);
    }

    @Test
    void fileNameHasNumberDateAndCompanyWithoutQuotes() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Файл", null, 1, null, null, null)), LinesMode.APPEND);

        String name = documents.render(draft(t, t.getLots().get(0), dist(), false)).fileName();

        assertThat(name).matches("Запрос КП № \u2013 от \\d{2}\\.\\d{2}\\.\\d{4} \u2013 [^\"«»]+\\.docx");
    }
}
