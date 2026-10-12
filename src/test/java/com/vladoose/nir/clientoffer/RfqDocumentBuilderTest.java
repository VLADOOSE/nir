package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.RfqTexts;
import com.vladoose.nir.service.document.KpDocxRenderer;
import com.vladoose.nir.service.document.RfqDocumentBuilder;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Запрос на бланке читается обратно через POI (спека 2026-10-12 §6.4). */
class RfqDocumentBuilderTest {

    private final RfqDocumentBuilder builder = new RfqDocumentBuilder();
    private final KpDocxRenderer renderer = new KpDocxRenderer();

    private static final List<RfqDocumentBuilder.Line> LINES = List.of(
            new RfqDocumentBuilder.Line("Электрокардиограф BeneHeart R12 (Mindray)", "12 отведений, печать A4", "2", "шт",
                    "РУ № РК МИ (МТ)-0№030903"),
            new RfqDocumentBuilder.Line("Тонометр OMRON M2 (OMRON)", null, "уточняется", "упак", null));

    private static RfqDocumentBuilder.Input input(Long number, String client, List<RfqDocumentBuilder.Line> lines,
                                                  boolean stamp, CompanyProfile p) {
        return new RfqDocumentBuilder.Input(p, number, LocalDate.of(2026, 10, 12), "ТОО «MEDSYST»", "Иванов Иван",
                RfqTexts.of(null, Market.KZ), client, lines, "zakup@westmed.kz", LocalDate.of(2026, 10, 15), stamp);
    }

    private static XWPFDocument open(byte[] docx) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    private static String text(byte[] docx) throws IOException {
        try (XWPFDocument d = open(docx); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
            return x.getText().replace(' ', ' ');
        }
    }

    private static XWPFTable items(XWPFDocument d) {
        return d.getTables().stream().filter(t -> t.getRow(0).getCell(0).getText().equals("№")).findFirst().orElseThrow();
    }

    private static List<String> row(XWPFTable t, int i) {
        return t.getRow(i).getTableCells().stream().map(XWPFTableCell::getText).toList();
    }

    @Test
    void letterheadNumberRecipientItemsAskAndReplyLine() throws Exception {
        byte[] docx = renderer.render(builder.build(input(534L, null, LINES, false, KpFixtures.profileKz())));

        assertThat(text(docx)).contains("West-Med")
                .contains("Исх. № 534 от 12.10.2026 г.").contains("ТОО «MEDSYST»").contains("Иванов Иван")
                .contains("ЗАПРОС КОММЕРЧЕСКОГО ПРЕДЛОЖЕНИЯ")
                .contains("ТОО «West-Med» просит предоставить коммерческое предложение на поставку следующих товаров:")
                .contains("В предложении просим указать:").contains("1. Модель и производитель;")
                .contains("6. Условия доставки.").contains("Ответ просим направить на zakup@westmed.kz до 15.10.2026.")
                .contains("Директор ТОО «West-Med»").contains("Ширяев И. В.")
                // контакты под подписью («моб: …, e-mail: …») не печатаются; телефон и почта в реквизитах бланка — остаются
                .doesNotContain("Конечный заказчик").doesNotContain("Итого").doesNotContain("моб: 87770752770");
        try (XWPFDocument d = open(docx)) {
            XWPFTable t = items(d);
            assertThat(row(t, 0)).containsExactly("№", "Наименование", "Характеристики", "Кол-во", "Ед. изм.", "Регистрация в РК");
            assertThat(row(t, 1)).containsExactly("1", "Электрокардиограф BeneHeart R12 (Mindray)", "12 отведений, печать A4",
                    "2", "шт", "РУ № РК МИ (МТ)-0№030903");
            assertThat(row(t, 2).get(3)).isEqualTo("уточняется");
        }
    }

    @Test
    void emptyColumnsAreDropped_draftHasDashNumber() throws Exception {
        List<RfqDocumentBuilder.Line> plain = List.of(new RfqDocumentBuilder.Line("Кушетка медицинская", null, "1", "шт", null));
        byte[] docx = renderer.render(builder.build(input(null, null, plain, false, KpFixtures.profileKz())));
        assertThat(text(docx)).contains("Исх. № — от 12.10.2026 г.");
        try (XWPFDocument d = open(docx)) {
            assertThat(row(items(d), 0)).containsExactly("№", "Наименование", "Кол-во", "Ед. изм.");
        }
    }

    @Test
    void clientLineGoesUnderIntroOnlyWhenGiven() throws Exception {
        String line = "Конечный заказчик: ZZ Клиника, место поставки: г. Уральск. Просим закрепить проект за ТОО «West-Med».";
        assertThat(text(renderer.render(builder.build(input(1L, line, LINES, false, KpFixtures.profileKz()))))).contains(line);
    }

    @Test
    void stampAndSignatureOnlyWithFlag() throws Exception {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(KpTestSupport.circlePng());
        p.setSignaturePng(KpTestSupport.signaturePng());
        try (XWPFDocument d = open(renderer.render(builder.build(input(1L, null, LINES, false, p))))) {
            assertThat(anchors(d)).isZero();
        }
        try (XWPFDocument d = open(renderer.render(builder.build(input(1L, null, LINES, true, p))))) {
            assertThat(anchors(d)).isEqualTo(2);
        }
    }

    private static int anchors(XWPFDocument d) {
        List<XWPFParagraph> all = new ArrayList<>(d.getParagraphs());
        for (XWPFTable t : d.getTables()) {
            for (XWPFTableRow r : t.getRows()) for (XWPFTableCell c : r.getTableCells()) all.addAll(c.getParagraphs());
        }
        int n = 0;
        for (XWPFParagraph p : all) {
            for (XWPFRun r : p.getRuns()) for (CTDrawing drawing : r.getCTR().getDrawingList()) n += drawing.sizeOfAnchorArray();
        }
        return n;
    }
}
