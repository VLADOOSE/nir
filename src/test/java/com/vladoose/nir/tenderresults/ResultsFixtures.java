package com.vladoose.nir.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.tenderresults.TenderResultsWriter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Тендеры goszakup для тестов итогов: номер объявления «<id>-1», лоты с кодами площадки. */
public final class ResultsFixtures {

    private ResultsFixtures() {}

    /** lots — пары «код лота площадки, наименование». Имя другое, чем у {@link #tender}: две перегрузки с String... неоднозначны. */
    public static Tender tenderIn(TenderRepository tenders, long buyId, LocalDateTime deadlineAt, String region, String... lots) {
        Tender t = Tender.builder().tenderNumber(buyId + "-1").sourceExtId(buyId + "-1").status("COMPLETED")
                .source(Source.PUBLIC_TENDER).platform(TenderPlatform.GOSZAKUP).market(Market.KZ).currency("KZT")
                .description("ZZ закупка " + buyId).customerName("ГКП «ZZ больница»").customerBin("110240019426")
                .region(region).deadline(deadlineAt.toLocalDate()).deadlineAt(deadlineAt).build();
        for (int i = 0; i + 1 < lots.length; i += 2) {
            t.getLots().add(TenderLot.builder().tender(t).sourceLotCode(lots[i]).equipName(lots[i + 1]).quantity(3)
                    .maxCost(new BigDecimal("613900.00")).techSpecStatus(TechSpecStatus.PENDING).build());
        }
        return tenders.saveAndFlush(t);
    }

    public static Tender tender(TenderRepository tenders, long buyId, LocalDateTime deadlineAt, String... lots) {
        return tenderIn(tenders, buyId, deadlineAt, "Западно-Казахстанская область", lots);
    }

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 10, 0);
    public static final LocalDate TODAY = NOW.toLocalDate();

    public static final String URL = "https://ows.goszakup.gov.kz/download/trd_buy/zz";

    /** Объявление площадки: итоги и протокол (url пусто — файла нет). */
    public static ResultBuy buy(long buyId, String resultsAt, String protocolUrl, ResultLot... lots) {
        return new ResultBuy(buyId, buyId + "-1", 350, resultsAt, null, 3, "Запрос ценовых предложений", List.of(lots),
                protocolUrl == null ? List.of() : List.of(new ProtocolFileRef("protocol_PI_" + buyId + ".pdf", protocolUrl)));
    }

    public static ResultLot lot(long lotId, String code, int status, long planPoint) {
        String name = status == 360 ? "Закупка состоялась" : status == 370 ? "Закупка не состоялась"
                : status == 430 ? "Закупка отменена" : "Опубликован (прием ценовых предложений)";
        return new ResultLot(lotId, code, status, name, new BigDecimal("3"), new BigDecimal("1841700"), planPoint,
                "309911.100.000001", "Штука", "110240019426");
    }

    /** Наш лот: модель в работе. */
    public static void markOurs(JdbcTemplate jdbc, TenderLot lot) {
        Long eq = jdbc.queryForObject("insert into med_equipment (name, manufact, market) values ('ZZ модель', 'ZZ', 'KZ') returning id",
                Long.class);
        jdbc.update("update tender_lot set proposed_equipment_id = ? where id = ?", eq, lot.getId());
    }

    /** Текстовая фикстура протокола (как её отдаёт PDFBox) → PDF по 40 строк на широкой странице: проход качает и разбирает его как файл площадки. */
    public static byte[] protocolPdf(String resource) throws Exception {
        String text;
        try (InputStream in = ResultsFixtures.class.getResourceAsStream("/goszakup/protocols/" + resource)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> lines = text.lines().map(l -> l.replace('\t', ' ').strip()).filter(l -> !l.isEmpty()).toList();
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             InputStream font = ResultsFixtures.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            PDType0Font f = PDType0Font.load(d, font);
            for (int i = 0; i < lines.size(); i += 40) {
                PDPage page = new PDPage(new PDRectangle(2400, 900));
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(f, 9);
                    cs.newLineAtOffset(20, 860);
                    for (String l : lines.subList(i, Math.min(lines.size(), i + 40))) {
                        cs.showText(l);
                        cs.newLineAtOffset(0, -18);
                    }
                    cs.endText();
                }
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    /** Попытка закупки по пункту плана; способ — по номеру лота (ЗЦП / ОИ / ОК). */
    public static AttemptLot attempt(long lotId, String lotNumber, int status, long point, String anno, String publish, String end,
                                     String results, String supplierBin) {
        String method = lotNumber.contains("-ОИ") ? "Из одного источника по несостоявшимся закупкам" : "Запрос ценовых предложений";
        return new AttemptLot(lotId, lotNumber, status, status == 360 ? "Закупка состоялась" : status == 370 ? "Закупка не состоялась" : "Опубликован",
                point, anno, TenderResultsWriter.buyId(anno), lotNumber.contains("-ОИ") ? 6 : 3, method, publish, end, results, supplierBin);
    }
}
