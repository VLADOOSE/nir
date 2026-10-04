package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocument;
import com.vladoose.nir.service.document.KpDocxRenderer;
import com.vladoose.nir.service.document.KpPageGeometry;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlError;
import org.apache.xmlbeans.XmlOptions;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromH;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromV;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Word читается обратно через POI: колонки, сквозная шапка, явные ширины, объединения, печать (спека §6.4). */
class KpDocxRendererTest {

    private static final double TWIPS_PER_MM = 1440 / 25.4;
    private static final double EMU_PER_MM = 36_000;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private final KpDocxRenderer renderer = new KpDocxRenderer();

    private byte[] docx(ClientOffer o, CompanyProfile p) {
        return renderer.render(KpFixtures.document(o, p));
    }

    private static XWPFDocument open(byte[] docx) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    private static XWPFTable itemsTable(XWPFDocument d) {
        return d.getTables().stream()
                .filter(t -> t.getRow(0).getCell(0).getText().equals("№"))
                .findFirst().orElseThrow();
    }

    /** Все абзацы — тела и ячеек таблиц: печать стоит в ячейке линии подписи. */
    private static List<XWPFParagraph> allParagraphs(XWPFDocument d) {
        List<XWPFParagraph> all = new ArrayList<>(d.getParagraphs());
        for (XWPFTable t : d.getTables()) {
            for (XWPFTableRow r : t.getRows()) for (XWPFTableCell c : r.getTableCells()) all.addAll(c.getParagraphs());
        }
        return all;
    }

    private static int anchors(XWPFDocument d) {
        int n = 0;
        for (XWPFParagraph p : allParagraphs(d)) {
            for (XWPFRun r : p.getRuns()) {
                for (CTDrawing drawing : r.getCTR().getDrawingList()) n += drawing.sizeOfAnchorArray();
            }
        }
        return n;
    }

    @Test
    void itemsTableHasColumnsInOrderRepeatingHeaderAndFixedWidths() throws Exception {
        try (XWPFDocument d = open(docx(KpFixtures.offer2409(), KpFixtures.profileKz()))) {
            XWPFTable t = itemsTable(d);
            List<String> header = t.getRow(0).getTableCells().stream().map(XWPFTableCell::getText).toList();
            assertThat(header).containsExactly("№", "Наименование", "Ед. изм.", "Кол-во", "Цена за ед., тг", "НДС",
                    "Общая сумма, тг", "Регистрация в РК");
            assertThat(t.getRow(0).isRepeatHeader()).isTrue();
            CTTblPr pr = t.getCTTbl().getTblPr();
            assertThat(pr.getTblW().getType()).isEqualTo(STTblWidth.DXA);
            assertThat(pr.getTblLayout().getType()).isEqualTo(STTblLayoutType.FIXED);
            long sum = 0;
            for (XWPFTableCell c : t.getRow(1).getTableCells()) sum += ((BigInteger) c.getCTTc().getTcPr().getTcW().getW()).longValue();
            assertThat(sum).isEqualTo(((BigInteger) pr.getTblW().getW()).longValue());
            assertThat(t.getRow(1).getCell(1).getText()).isEqualTo("Пульсоксиметр QMP – PO70 взрослый");
            assertThat(t.getRow(1).getCell(4).getText().replace(' ', ' ')).isEqualTo("105 600,00");
        }
    }

    @Test
    void sectionAndIncludedRowsAreMerged() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        ClientOfferItem section = ClientOfferTestData.item(o, 0, "Основные комплектующие:", null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        o.getItems().add(0, section);
        ClientOfferItem included = ClientOfferTestData.item(o, 99, "Гарантийное сервисное обслуживание 37 месяцев", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        o.getItems().add(included);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            XWPFTable t = itemsTable(d);
            XWPFTableRow sectionRow = t.getRow(1);
            assertThat(sectionRow.getTableCells()).hasSize(1);
            assertThat(sectionRow.getCell(0).getCTTc().getTcPr().getGridSpan().getVal().intValue()).isEqualTo(8);
            assertThat(sectionRow.getCell(0).getText()).isEqualTo("Основные комплектующие:");
            XWPFTableRow last = t.getRow(t.getNumberOfRows() - 1);
            assertThat(last.getTableCells()).hasSize(3);          // №, наименование, «включено» на 6 колонок
            assertThat(last.getCell(2).getCTTc().getTcPr().getGridSpan().getVal().intValue()).isEqualTo(6);
            assertThat(last.getCell(2).getText()).isEqualTo("Включено в стоимость");
        }
    }

    @Test
    void letterheadTotalsWordsTermsAndSignoffArePresent() throws Exception {
        try (XWPFDocument d = open(docx(KpFixtures.offer2409(), KpFixtures.profileKz()));
             XWPFWordExtractor extractor = new XWPFWordExtractor(d)) {
            String text = extractor.getText().replace(' ', ' ');
            assertThat(text).contains("Жауапкершілігі", "РНН 271 800 059 535 БИН 121 040 000 303",
                    "Исх. № 443 от 14.09.2026 г.", "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ",
                    "Итого: 748 060,00 тг", "в т.ч. НДС 16%: 53 649,65 тг",
                    "Сумма прописью: Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын",
                    "1. Цены действительны в течение 10 дней;", "Директор ТОО «West-Med»", "Ширяев И. В.");
        }
    }

    @Test
    void stampIsFloatingPictureOnlyWithCheckbox() throws Exception {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(KpTestSupport.circlePng());
        p.setSignaturePng(KpTestSupport.signaturePng());
        ClientOffer o = KpFixtures.offer2409();
        try (XWPFDocument d = open(docx(o, p))) {
            assertThat(anchors(d)).isZero();
            assertThat(d.getAllPictures()).isEmpty();
        }
        o.setWithStamp(true);
        try (XWPFDocument d = open(docx(o, p))) {
            assertThat(anchors(d)).isEqualTo(2);                 // печать и подпись «перед текстом» (спека §6.4)
            assertThat(d.getAllPictures()).hasSize(2);
        }
    }

    @Test
    void landscapeSwapsPageSizeAndFontIsTimesNewRoman() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        o.setLandscape(true);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            CTPageSz size = d.getDocument().getBody().getSectPr().getPgSz();
            assertThat(((BigInteger) size.getW()).intValue()).isGreaterThan(((BigInteger) size.getH()).intValue());
            assertThat(size.getOrient()).isEqualTo(STPageOrientation.LANDSCAPE);
            XWPFRun firstRun = d.getParagraphs().stream().flatMap(p -> p.getRuns().stream()).findFirst().orElseThrow();
            assertThat(firstRun.getFontFamily()).isEqualTo("Times New Roman");
        }
    }

    /** Шрифт Word — Times New Roman у каждого куска текста, в таблицах тоже; им же — то, что допечатают (по умолчанию). */
    @Test
    void everyRunIsTimesNewRoman() throws Exception {
        ClientOffer o = withSectionAndIncluded(KpFixtures.offer2409());
        o.setTermsStyle(TermsStyle.TABLE);
        o.setSignoffContacts(true);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            int runs = 0;
            for (XWPFParagraph p : allParagraphs(d)) {
                for (XWPFRun r : p.getRuns()) {
                    assertThat(r.getFontFamily()).as("«%s»", r.text()).isEqualTo("Times New Roman");
                    runs++;
                }
            }
            assertThat(runs).isGreaterThan(50);
            assertThat(d.getStyles().getDefaultRunStyle().getFontSizeAsDouble()).isEqualTo(11.0);
        }
    }

    /**
     * Поля листа и ширина набора — из той же геометрии, что @page PDF (KpPageGeometry); таблица позиций — во всю ширину
     * набора, колонки — долями модели (последняя добирает остаток округления).
     */
    @Test
    void pageMarginsAndTableWidthComeFromTheSharedGeometry() throws Exception {
        for (boolean landscape : new boolean[] {false, true}) {
            ClientOffer o = KpFixtures.offer2409();
            o.setLandscape(landscape);
            KpDocument doc = KpFixtures.document(o, KpFixtures.profileKz());
            try (XWPFDocument d = open(renderer.render(doc))) {
                String as = landscape ? "альбомная" : "книжная";
                CTSectPr sect = d.getDocument().getBody().getSectPr();
                assertThat(value(sect.getPgSz().getW())).as(as).isEqualTo(twips(KpPageGeometry.pageWidthMm(landscape)));
                assertThat(value(sect.getPgSz().getH())).as(as).isEqualTo(twips(KpPageGeometry.pageHeightMm(landscape)));
                KpPageGeometry.Margins m = KpPageGeometry.margins(landscape);
                CTPageMar mar = sect.getPgMar();
                assertThat(value(mar.getTop())).as(as).isEqualTo(twips(m.top()));
                assertThat(value(mar.getRight())).as(as).isEqualTo(twips(m.right()));
                assertThat(value(mar.getBottom())).as(as).isEqualTo(twips(m.bottom()));
                assertThat(value(mar.getLeft())).as(as).isEqualTo(twips(m.left()));
                long text = value(sect.getPgSz().getW()) - value(mar.getLeft()) - value(mar.getRight());
                XWPFTable t = itemsTable(d);
                assertThat(value(t.getCTTbl().getTblPr().getTblW().getW())).as(as).isEqualTo(text);
                List<CTTblGridCol> grid = t.getCTTbl().getTblGrid().getGridColList();
                assertThat(grid).hasSize(doc.columns().size());
                long sum = 0;
                for (int i = 0; i < grid.size(); i++) {
                    long w = value(grid.get(i).getW());
                    sum += w;
                    if (i < grid.size() - 1) {
                        assertThat((double) w).as("%s: %s", as, doc.columns().get(i).key())
                                .isCloseTo(text * doc.columns().get(i).percent() / 100.0, within(1.0));
                    }
                }
                assertThat(sum).as(as).isEqualTo(text);
            }
        }
    }

    /**
     * Кегль таблицы позиций — кегль модели (KpDocument.tableFontPt) у шапки, строк, объединённых ячеек и у знаков абзацев
     * (по знаку абзаца Word считает высоту строки ячейки): обычная таблица — 10 pt, тесная — 9,5 pt.
     */
    @Test
    void itemsTableRunsUseTheModelTypeSize() throws Exception {
        ClientOffer o = withSectionAndIncluded(KpFixtures.offer2409());
        for (boolean crowded : new boolean[] {false, true}) {
            if (crowded) o.getTableColumns().add(new OfferColumn("COUNTRY", null));
            KpDocument doc = KpFixtures.document(o, KpFixtures.profileKz());
            assertThat(doc.tableFontPt()).isEqualTo(crowded ? 9.5 : 10.0);
            try (XWPFDocument d = open(renderer.render(doc))) {
                int runs = 0;
                for (XWPFTableRow row : itemsTable(d).getRows()) {
                    for (XWPFTableCell c : row.getTableCells()) {
                        for (XWPFParagraph p : c.getParagraphs()) {
                            assertThat(markPt(p)).as("знак абзаца «%s»", p.getText()).isEqualTo(doc.tableFontPt());
                            for (XWPFRun r : p.getRuns()) {
                                assertThat(r.getFontSizeAsDouble()).as("«%s»", r.text()).isEqualTo(doc.tableFontPt());
                                runs++;
                            }
                        }
                    }
                }
                assertThat(runs).isGreaterThan(40);
            }
        }
    }

    /** w:noWrap — ровно у ячеек строк в неразрывных колонках (KpDocument.Column.nowrap); у шапки и объединённых — нет. */
    @Test
    void noWrapMarksExactlyTheBodyCellsOfNoWrapColumns() throws Exception {
        KpDocument doc = KpFixtures.document(withSectionAndIncluded(KpFixtures.offer2409()), KpFixtures.profileKz());
        assertThat(doc.columns()).anyMatch(KpDocument.Column::nowrap).anyMatch(c -> !c.nowrap());
        try (XWPFDocument d = open(renderer.render(doc))) {
            XWPFTable t = itemsTable(d);
            for (XWPFTableCell c : t.getRow(0).getTableCells()) assertThat(noWrap(c)).as("шапка «%s»", c.getText()).isFalse();
            assertThat(t.getNumberOfRows()).isEqualTo(doc.rows().size() + 1);
            for (int r = 0; r < doc.rows().size(); r++) {
                KpDocument.Row row = doc.rows().get(r);
                List<XWPFTableCell> cells = t.getRow(r + 1).getTableCells();
                for (int i = 0; i < row.cells().size(); i++) {
                    assertThat(noWrap(cells.get(i))).as("строка %d, колонка %s", r, doc.columns().get(i).key())
                            .isEqualTo(doc.columns().get(i).nowrap());
                }
                if (row.spanFrom() < doc.columns().size()) {
                    assertThat(noWrap(cells.get(row.spanFrom()))).as("объединённая ячейка строки %d", r).isFalse();
                }
            }
        }
    }

    /**
     * Поля ячеек таблиц позиций и условий — поля ячеек PDF (KpPageGeometry: 1,5 мм по бокам, 1,2 мм сверху и снизу): доли
     * колонок посчитаны под них, а у Word по умолчанию 1,9 мм — колонки денег вышли бы уже. Рамка — на поле листа: в режиме
     * Word 2013+ (compatibilityMode 15) отступ таблицы отсчитывается до рамки — он нулевой.
     */
    @Test
    void itemsAndTermsTablesUseThePdfCellPadding() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        o.setTermsStyle(TermsStyle.TABLE);
        long h = twips(KpPageGeometry.CELL_PADDING_H_MM), v = twips(KpPageGeometry.CELL_PADDING_V_MM);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            for (XWPFTable t : List.of(itemsTable(d), table(d, "Порядок оплаты"))) {
                CTTblPr pr = t.getCTTbl().getTblPr();
                CTTblCellMar mar = pr.getTblCellMar();
                assertThat(List.of(mar.getLeft(), mar.getRight(), mar.getTop(), mar.getBottom(), pr.getTblInd()))
                        .allSatisfy(w -> assertThat(w.getType()).isEqualTo(STTblWidth.DXA));
                assertThat(value(mar.getLeft().getW())).isEqualTo(h);
                assertThat(value(mar.getRight().getW())).isEqualTo(h);
                assertThat(value(mar.getTop().getW())).isEqualTo(v);
                assertThat(value(mar.getBottom().getW())).isEqualTo(v);
                assertThat(value(pr.getTblInd().getW())).isZero();
            }
        }
    }

    /**
     * Печать — как в PDF: центр у начала линии подписи (у левого края её ячейки) и на 4 мм выше линии — у KZ и у длинной
     * должности (РФ), печать 40 и 30 мм; картинка «перед текстом», в ячейке, её можно сдвинуть; поверх подписи (в PDF печать
     * рисуется после подписи), у каждой картинки свой id.
     */
    @Test
    void stampCentreSitsAtTheStartOfTheSignLineFourMmAboveIt() throws Exception {
        for (CompanyProfile p : List.of(KpFixtures.profileKz(), profileWithLongTitle())) {
            for (int size : new int[] {40, 30}) {
                p.setStampPng(KpTestSupport.circlePng());
                p.setSignaturePng(KpTestSupport.signaturePng());
                p.setStampSizeMm(size);
                ClientOffer o = KpFixtures.offer2409();
                o.setWithStamp(true);
                String as = p.getShortName() + ", " + size + " мм";
                try (XWPFDocument d = open(docx(o, p))) {
                    Floating s = floating(d, "Печать");
                    XWPFTableRow row = s.cell().getTableRow();
                    int at = row.getTableCells().indexOf(s.cell());
                    assertThat(at).as(as).isEqualTo(1);
                    assertThat(row.getCell(0).getText()).as(as).isEqualTo("Директор " + p.getShortName());
                    assertThat(s.cell().getCTTc().getTcPr().getTcBorders().getBottom().getVal()).as(as).isEqualTo(STBorder.SINGLE);
                    assertThat(s.extentMm()[0]).as(as + ": ширина печати").isCloseTo(size, within(0.01));
                    double[] centre = s.centreFromLineMm();
                    assertThat(centre[0]).as(as + ": центр от начала линии").isCloseTo(KpPageGeometry.STAMP_CENTER_X_MM, within(0.01));
                    assertThat(centre[1]).as(as + ": центр от линии").isCloseTo(KpPageGeometry.STAMP_CENTER_Y_MM, within(0.01));
                    CTAnchor a = s.anchor();
                    assertThat(a.isSetWrapNone()).as(as).isTrue();
                    assertThat(a.getBehindDoc()).as(as).isFalse();
                    assertThat(a.getLayoutInCell()).as(as).isTrue();
                    assertThat(a.getAllowOverlap()).as(as).isTrue();
                    Floating signature = floating(d, "Подпись");
                    assertThat(signature.cell()).as(as).isSameAs(s.cell());
                    assertThat(a.getRelativeHeight()).as(as + ": печать поверх подписи").isGreaterThan(signature.anchor().getRelativeHeight());
                    assertThat(a.getDocPr().getId()).as(as + ": id картинок").isNotEqualTo(signature.anchor().getDocPr().getId());
                }
            }
        }
    }

    /**
     * Подпись от компании: линии нет — печать центром у конца названия (у края ячейки за ним), на 4 мм выше низа строки;
     * ячейка названия — по ширине строки, как в PDF (там печать — у конца нарисованного названия).
     */
    @Test
    void companyStampSitsAtTheEndOfTheCompanyName() throws Exception {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(KpTestSupport.circlePng());
        ClientOffer o = KpFixtures.offer2409();
        o.setSignoff(OfferSignoff.COMPANY);
        o.setWithStamp(true);
        o.setIntro(null);   // иначе первое «ТОО «West-Med»» в PDF — во вводной
        byte[] pdf = KpFixtures.pdf(o, p);
        double pdfNameEnd = KpTestSupport.find(pdf, "ТОО «West-Med»").right() - KpPageGeometry.margins(false).left();
        try (XWPFDocument d = open(docx(o, p))) {
            Floating s = floating(d, "Печать");
            XWPFTableRow row = s.cell().getTableRow();
            assertThat(row.getTableCells().indexOf(s.cell())).isEqualTo(1);
            assertThat(row.getCell(0).getText()).isEqualTo("ТОО «West-Med»");
            assertThat(row.getCell(0).getVerticalAlignment()).isEqualTo(XWPFTableCell.XWPFVertAlign.BOTTOM);
            assertThat(cellWidth(row.getCell(0)) / TWIPS_PER_MM).as("конец названия").isBetween(pdfNameEnd - 0.1, pdfNameEnd + 1.0);
            double[] centre = s.centreFromLineMm();
            assertThat(centre[0]).isCloseTo(KpPageGeometry.STAMP_CENTER_X_MM, within(0.01));
            assertThat(centre[1]).isCloseTo(KpPageGeometry.STAMP_CENTER_Y_MM, within(0.01));
        }
    }

    /**
     * Подпись от компании без печати — «С уважением,» / название / контакты 10 pt строкой ниже, без должности и фамилии;
     * блок подписи не отрывается на другую страницу от своего начала (в PDF — page-break-inside: avoid).
     */
    @Test
    void companySignoffWithContactsKeepsTogether() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        o.setSignoff(OfferSignoff.COMPANY);
        o.setSignoffContacts(true);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            List<String> tail = d.getParagraphs().stream().map(XWPFParagraph::getText).toList();
            assertThat(tail.subList(tail.size() - 3, tail.size())).containsExactly(
                    "С уважением,", "ТОО «West-Med»", "моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru");
            List<XWPFParagraph> body = d.getParagraphs();
            assertThat(body.get(body.size() - 3).isKeepNext()).isTrue();
            assertThat(body.get(body.size() - 2).isKeepNext()).isTrue();
            assertThat(body.get(body.size() - 1).getRuns().get(0).getFontSizeAsDouble()).isEqualTo(10.0);
            try (XWPFWordExtractor x = new XWPFWordExtractor(d)) {
                assertThat(x.getText()).doesNotContain("Директор", "Ширяев");
            }
        }
        o.setSignoff(OfferSignoff.DIRECTOR);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            assertThat(paragraph(d, "С уважением,").isKeepNext()).isTrue();
            assertThat(table(d, "Ширяев И. В.").getRow(0).getTableCells())
                    .allSatisfy(c -> assertThat(c.getParagraphs().get(0).isKeepNext()).isTrue());
        }
    }

    /** «Итого» — справа жирным 12 pt, разбивка НДС — справа обычным 11 pt (.totals). */
    @Test
    void totalsAreBoldOnTheRight() throws Exception {
        try (XWPFDocument d = open(docx(KpFixtures.offer2409(), KpFixtures.profileKz()))) {
            XWPFParagraph total = paragraph(d, "Итого:");
            assertThat(total.getAlignment()).isEqualTo(ParagraphAlignment.RIGHT);
            assertThat(total.getRuns().get(0).isBold()).isTrue();
            assertThat(total.getRuns().get(0).getFontSizeAsDouble()).isEqualTo(12.0);
            XWPFParagraph vat = paragraph(d, "в т.ч. НДС 5%");
            assertThat(vat.getAlignment()).isEqualTo(ParagraphAlignment.RIGHT);
            assertThat(vat.getRuns().get(0).isBold()).isFalse();
            assertThat(vat.getRuns().get(0).getFontSizeAsDouble()).isEqualTo(11.0);
        }
    }

    /**
     * Печать ложится там же, где в PDF: сдвиг её прямоугольника от начала и от верха линии подписи в Word (смещения якоря)
     * — как у нарисованных в PDF печати и линии; круглая и овальная печать, KZ и длинная должность РФ, 40 и 30 мм.
     */
    @Test
    void stampLiesWhereThePdfPutsIt() throws Exception {
        for (boolean oval : new boolean[] {false, true}) {
            byte[] png = oval ? ovalPng() : KpTestSupport.circlePng();
            for (CompanyProfile p : List.of(KpFixtures.profileKz(), profileWithLongTitle())) {
                for (int size : new int[] {40, 30}) {
                    p.setStampPng(png);
                    p.setStampSizeMm(size);
                    ClientOffer o = KpFixtures.offer2409();
                    o.setWithStamp(true);
                    String as = p.getShortName() + ", " + size + " мм, " + (oval ? "овальная" : "круглая");
                    byte[] pdf = KpFixtures.pdf(o, p);
                    KpTestSupport.Box line = signLine(pdf, KpTestSupport.find(pdf, "Директор " + p.getShortName()));
                    List<KpTestSupport.Box> images = KpTestSupport.imageBoxes(pdf);
                    assertThat(images).as(as).hasSize(1);
                    KpTestSupport.Box pdfStamp = images.get(0);
                    try (XWPFDocument d = open(docx(o, p))) {
                        Floating s = floating(d, "Печать");
                        double[] box = s.boxFromLineMm();
                        assertThat(box[0]).as(as + ": левый край от начала линии").isCloseTo(pdfStamp.left() - line.left(), within(0.3));
                        assertThat(box[1]).as(as + ": верх от линии").isCloseTo(pdfStamp.top() - line.top(), within(0.3));
                        assertThat(s.extentMm()[0]).as(as + ": ширина").isCloseTo(pdfStamp.width(), within(0.3));
                        assertThat(s.extentMm()[1]).as(as + ": высота").isCloseTo(pdfStamp.height(), within(0.3));
                    }
                }
            }
        }
    }

    /**
     * «Должность ____ Фамилия» — как в PDF: таблица подписи от поля листа, ячейка должности — по ширине строки (линия
     * сразу за должностью, Word с запасом на округление до 1 мм), линия 45 мм, фамилия в 3 мм после линии.
     */
    @Test
    void signLineFollowsTheTitleAsInThePdf() throws Exception {
        for (CompanyProfile p : List.of(KpFixtures.profileKz(), profileWithLongTitle())) {
            ClientOffer o = KpFixtures.offer2409();
            byte[] pdf = KpFixtures.pdf(o, p);
            String title = "Директор " + p.getShortName();
            KpTestSupport.Box line = signLine(pdf, KpTestSupport.find(pdf, title));
            double pdfLineStart = line.left() - (float) KpPageGeometry.margins(false).left();
            try (XWPFDocument d = open(docx(o, p))) {
                XWPFTable t = table(d, title);
                CTTblPr pr = t.getCTTbl().getTblPr();
                assertThat(value(pr.getTblInd().getW())).isZero();
                assertThat(value(pr.getTblCellMar().getLeft().getW())).isZero();
                XWPFTableRow row = t.getRow(0);
                assertThat(cellWidth(row.getCell(0)) / TWIPS_PER_MM).as(p.getShortName() + ": начало линии")
                        .isBetween(pdfLineStart - 0.1, pdfLineStart + 1.0);
                assertThat(cellWidth(row.getCell(1)) / TWIPS_PER_MM).isCloseTo(KpPageGeometry.SIGN_LINE_WIDTH_MM, within(0.05));
                assertThat((double) line.width()).isCloseTo(KpPageGeometry.SIGN_LINE_WIDTH_MM, within(0.5));
                assertThat(row.getCell(2).getText()).isEqualTo("Ширяев И. В.");
                assertThat(row.getCell(2).getParagraphs().get(0).getIndentationLeft() / TWIPS_PER_MM)
                        .isCloseTo(KpPageGeometry.SIGN_NAME_GAP_MM, within(0.05));
                for (XWPFTableCell c : row.getTableCells()) {
                    assertThat(c.getVerticalAlignment()).isEqualTo(XWPFTableCell.XWPFVertAlign.BOTTOM);
                }
            }
        }
    }

    /**
     * Подпись — плавающая картинка в строке подписи, как печать (спека §6.4): по центру линии и нижним краем ровно на ней —
     * как в PDF, где подпись стоит на линии (встроенную Word ставил на базовую линию строки точной высоты — на 3 мм выше
     * линии). Строка подписи держит высоту PDF сама, без картинки. Размер — как в PDF, в пределах 44 × 15 мм: большая
     * уменьшается, маленькая не растягивается.
     */
    @Test
    void signatureFloatsWithItsBottomOnTheSignLine() throws Exception {
        for (byte[] png : List.of(KpTestSupport.signaturePng(), strokePng(60, 20))) {
            CompanyProfile p = KpFixtures.profileKz();
            p.setSignaturePng(png);
            ClientOffer o = KpFixtures.offer2409();
            o.setWithStamp(true);   // печати в реквизитах нет — рисуется одна подпись
            byte[] pdf = KpFixtures.pdf(o, p);
            List<KpTestSupport.Box> pdfImages = KpTestSupport.imageBoxes(pdf);
            assertThat(pdfImages).hasSize(1);
            KpTestSupport.Box pdfSignature = pdfImages.get(0);
            KpTestSupport.Box line = signLine(pdf, KpTestSupport.find(pdf, "Директор ТОО «West-Med»"));
            try (XWPFDocument d = open(docx(o, p))) {
                assertThat(anchors(d)).isEqualTo(1);
                Floating s = floating(d, "Подпись");
                assertThat(s.cell().getTableRow().getTableCells().indexOf(s.cell())).as("ячейка линии").isEqualTo(1);
                assertThat(s.paragraph().getRuns()).allSatisfy(r -> assertThat(r.getCTR().getDrawingList())
                        .allSatisfy(dr -> assertThat(dr.sizeOfInlineArray()).isZero()));
                assertThat(s.lineMm()).as("высота строки подписи").isCloseTo(KpPageGeometry.SIGN_HEIGHT_MM, within(0.02));
                double[] box = s.boxFromLineMm();
                assertThat(box[3]).as("нижний край от линии").isCloseTo(0, within(0.01));
                assertThat((box[0] + box[2]) / 2).as("центр от начала линии")
                        .isCloseTo(cellWidth(s.cell()) / TWIPS_PER_MM / 2, within(0.01));
                double[] size = s.extentMm();
                assertThat(size[0]).isCloseTo(pdfSignature.width(), within(0.2)).isLessThanOrEqualTo(KpPageGeometry.SIGNATURE_MAX_WIDTH_MM + 0.01);
                assertThat(size[1]).isCloseTo(pdfSignature.height(), within(0.2)).isLessThanOrEqualTo(KpPageGeometry.SIGN_HEIGHT_MM + 0.01);
                assertThat(box[0]).as("левый край от начала линии — как в PDF").isCloseTo(pdfSignature.left() - line.left(), within(0.3));
                assertThat(box[3]).as("нижний край от линии — как в PDF").isCloseTo(pdfSignature.bottom() - line.top(), within(0.3));
                CTAnchor a = s.anchor();
                assertThat(a.isSetWrapNone()).isTrue();
                assertThat(a.getBehindDoc()).isFalse();
                assertThat(a.getLayoutInCell()).isTrue();
                assertThat(a.getAllowOverlap()).isTrue();
            }
        }
    }

    /**
     * Документ объявляет режим Word 2013+ (compatibilityMode 15): без него Word открывает КП «в режиме совместимости» — в
     * русском Office «[Режим ограниченной функциональности]» в заголовке окна, — а отступ таблиц считает по-старому.
     * Проверить это XMLBeans нельзя: в poi-ooxml-lite нет скомпилированного типа CT_Compat (validate падает на
     * отсутствующем ctcompat….xsb), поэтому settings.xml читается как XML и сверяется ровно с тем, что допускает ECMA-376:
     * compat — единственный элемент settings (порядок элементов CT_Settings не нарушить), в нём один compatSetting с тремя
     * обязательными атрибутами name / uri / val.
     */
    @Test
    void documentDeclaresWord2013CompatibilityMode() throws Exception {
        for (boolean landscape : new boolean[] {false, true}) {
            ClientOffer o = KpFixtures.offer2409();
            o.setLandscape(landscape);
            Element settings = part(docx(o, KpFixtures.profileKz()), "word/settings.xml").getDocumentElement();
            assertThat(List.of(settings.getNamespaceURI(), settings.getLocalName())).containsExactly(W, "settings");
            List<Element> children = elements(settings);
            assertThat(children).extracting(Element::getLocalName).containsExactly("compat");
            assertThat(children.get(0).getNamespaceURI()).isEqualTo(W);
            List<Element> compat = elements(children.get(0));
            assertThat(compat).extracting(Element::getLocalName).containsExactly("compatSetting");
            Element setting = compat.get(0);
            assertThat(setting.getNamespaceURI()).isEqualTo(W);
            int attributes = 0;
            for (int i = 0; i < setting.getAttributes().getLength(); i++) {
                if (!"http://www.w3.org/2000/xmlns/".equals(setting.getAttributes().item(i).getNamespaceURI())) attributes++;
            }
            assertThat(attributes).isEqualTo(3);
            assertThat(setting.getAttributeNS(W, "name")).isEqualTo("compatibilityMode");
            assertThat(setting.getAttributeNS(W, "uri")).isEqualTo("http://schemas.microsoft.com/office/word");
            assertThat(setting.getAttributeNS(W, "val")).isEqualTo("15");
        }
    }

    /** Логотип вместо названия — встроенной картинкой по центру, того же размера, что в PDF: не выше 20 мм, без растяжения. */
    @Test
    void logoReplacesBrandTextAtThePdfSize() throws Exception {
        for (byte[] png : List.of(KpTestSupport.circlePng(), strokePng(100, 30))) {
            CompanyProfile p = KpFixtures.profileKz();
            p.setLogoPng(png);
            ClientOffer o = KpFixtures.offer2409();
            List<KpTestSupport.Box> pdfImages = KpTestSupport.imageBoxes(KpFixtures.pdf(o, p));
            assertThat(pdfImages).hasSize(1);
            try (XWPFDocument d = open(docx(o, p)); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
                assertThat(x.getText()).doesNotContain("\"West-Med\"");
                XWPFParagraph logo = d.getParagraphs().stream()
                        .filter(par -> par.getRuns().stream().anyMatch(r -> !r.getEmbeddedPictures().isEmpty()))
                        .findFirst().orElseThrow();
                assertThat(logo.getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
                CTInline inline = logo.getRuns().get(0).getCTR().getDrawingArray(0).getInlineArray(0);
                assertThat(inline.getExtent().getCx() / EMU_PER_MM).isCloseTo(pdfImages.get(0).width(), within(0.2));
                assertThat(inline.getExtent().getCy() / EMU_PER_MM).isCloseTo(pdfImages.get(0).height(), within(0.2));
            }
        }
    }

    /** Предмет и вводная — как их набрал оператор: каждая строка — своей строкой (перенос), пустая строка — тоже. */
    @Test
    void subjectAndIntroKeepOperatorLineBreaks() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        o.setSubject("Поставка пульсоксиметров\r\nдля приёмного отделения");
        o.setIntro("Уважаемый Иван Петрович!\n\nПредлагаем поставку по следующим ценам:");
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            assertThat(paragraph(d, "Поставка пульсоксиметров").getText())
                    .isEqualTo("Поставка пульсоксиметров\nдля приёмного отделения");
            assertThat(paragraph(d, "Уважаемый Иван Петрович!").getText())
                    .isEqualTo("Уважаемый Иван Петрович!\n\nПредлагаем поставку по следующим ценам:");
        }
    }

    /**
     * Отступы между блоками — поля CSS шаблона со «схлопыванием» (большее из двух): заголовок на 3 мм ниже «Исх. №»
     * (.meta 3 мм против .title 2 мм); вводная → таблица позиций — 2 мм «после» вводной; таблица условий → таблица
     * позиций — пустой абзац в 4 мм (две таблицы подряд Word склеил бы в одну); «С уважением,» — на 10 мм ниже условий
     * списком плюс их нижнее поле 1 мм.
     */
    @Test
    void blocksAreSpacedLikeTheTemplate() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            assertThat(paragraph(d, "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ").getSpacingBefore()).isEqualTo(twips(3));
            assertThat(paragraph(d, "ТОО «West-Med» предлагает").getSpacingAfter()).isEqualTo(twips(2));
            assertThat(paragraph(d, "С уважением,").getSpacingBefore()).isEqualTo(twips(1) + twips(10));
        }
        o.setTermsStyle(TermsStyle.TABLE);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            List<IBodyElement> body = d.getBodyElements();
            int terms = body.indexOf(table(d, "Порядок оплаты"));
            assertThat(body.get(terms + 1)).isInstanceOf(XWPFParagraph.class);
            assertThat(exactLineMm((XWPFParagraph) body.get(terms + 1))).isCloseTo(4, within(0.02));
            assertThat(body.get(terms + 2)).isSameAs(itemsTable(d));
            assertThat(paragraph(d, "С уважением,").getSpacingBefore()).isEqualTo(twips(10));
        }
    }

    /** Шапка таблицы позиций — жирным по центру (.items th); строки не рвутся между страницами (page-break-inside: avoid). */
    @Test
    void headerIsBoldCenteredAndRowsDoNotBreakAcrossPages() throws Exception {
        try (XWPFDocument d = open(docx(withSectionAndIncluded(KpFixtures.offer2409()), KpFixtures.profileKz()))) {
            XWPFTable t = itemsTable(d);
            for (XWPFTableCell c : t.getRow(0).getTableCells()) {
                XWPFParagraph p = c.getParagraphs().get(0);
                assertThat(p.getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
                assertThat(p.getRuns()).allSatisfy(r -> assertThat(r.isBold()).isTrue());
            }
            assertThat(t.getRows()).allSatisfy(r -> assertThat(r.isCantSplitRow()).isTrue());
            XWPFTableCell section = t.getRow(1).getCell(0);
            assertThat(section.getParagraphs().get(0).getAlignment()).isEqualTo(ParagraphAlignment.LEFT);
            assertThat(section.getParagraphs().get(0).getRuns().get(0).isBold()).isTrue();
            XWPFTableCell included = t.getRow(t.getNumberOfRows() - 1).getCell(2);
            assertThat(included.getParagraphs().get(0).getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
            assertThat(included.getParagraphs().get(0).getRuns().get(0).isBold()).isFalse();
            XWPFTableCell price = t.getRow(2).getCell(4);
            assertThat(price.getParagraphs().get(0).getAlignment()).isEqualTo(ParagraphAlignment.RIGHT);
        }
    }

    /** Условия таблицей — подпись 45 % жирным, значение — остальное, 10,5 pt, рамки 0,5 pt (.terms-table). */
    @Test
    void termsTableHasBoldLabelsInTheirShare() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        o.setTermsStyle(TermsStyle.TABLE);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            XWPFTable t = table(d, "Порядок оплаты");
            long width = value(t.getCTTbl().getTblPr().getTblW().getW());
            List<CTTblGridCol> grid = t.getCTTbl().getTblGrid().getGridColList();
            assertThat((double) value(grid.get(0).getW())).isCloseTo(width * 0.45, within(1.0));
            XWPFTableRow row = t.getRows().stream().filter(r -> r.getCell(0).getText().equals("Порядок оплаты")).findFirst().orElseThrow();
            assertThat(row.getCell(0).getParagraphs().get(0).getRuns().get(0).isBold()).isTrue();
            assertThat(row.getCell(1).getText()).isEqualTo("100% предоплата");
            assertThat(row.getCell(1).getParagraphs().get(0).getRuns().get(0).isBold()).isFalse();
            assertThat(row.getCell(1).getParagraphs().get(0).getRuns().get(0).getFontSizeAsDouble()).isEqualTo(10.5);
            assertThat(t.getTopBorderType()).isEqualTo(XWPFTable.XWPFBorderType.SINGLE);
            assertThat(t.getInsideVBorderSize()).isEqualTo(4);
        }
    }

    /**
     * Крайние рамки таблиц позиций и условий — те же 0,5 pt, что внутренние. Word рамку по полю листа не режет (в PDF
     * openhtmltopdf срезал внешнюю половину крайних рамок — KpPdfDocumentTest.noLineIsCutByThePageMargins), поэтому
     * в Word достаточно, чтобы крайние рамки были заданы той же чертой.
     */
    @Test
    void outerTableBordersAreAsThickAsInnerOnes() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        o.setTermsStyle(TermsStyle.TABLE);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            for (XWPFTable t : List.of(itemsTable(d), table(d, "Порядок оплаты"))) {
                assertThat(List.of(t.getTopBorderType(), t.getBottomBorderType(), t.getLeftBorderType(), t.getRightBorderType(),
                        t.getInsideHBorderType(), t.getInsideVBorderType())).containsOnly(XWPFTable.XWPFBorderType.SINGLE);
                assertThat(List.of(t.getTopBorderSize(), t.getBottomBorderSize(), t.getLeftBorderSize(), t.getRightBorderSize(),
                        t.getInsideHBorderSize(), t.getInsideVBorderSize())).containsOnly(4);
            }
        }
    }

    /** Бланк — как в PDF: название вместо логотипа 30 pt жирным по центру, строки бланка по центру жирным, под ними черта 2,5 pt. */
    @Test
    void letterheadMirrorsThePdf() throws Exception {
        try (XWPFDocument d = open(docx(KpFixtures.offer2409(), KpFixtures.profileKz()))) {
            XWPFParagraph brand = paragraph(d, "\"West-Med\"");
            assertThat(brand.getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
            assertThat(brand.getRuns().get(0).isBold()).isTrue();
            assertThat(brand.getRuns().get(0).getFontSizeAsDouble()).isEqualTo(30.0);
            XWPFParagraph lines = paragraph(d, "РНН 271 800 059 535");
            assertThat(lines.getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
            assertThat(lines.getRuns().get(0).isBold()).isTrue();
            List<XWPFParagraph> body = d.getParagraphs();
            CTBorder rule = body.get(body.indexOf(lines) + 1).getCTP().getPPr().getPBdr().getBottom();
            assertThat(rule.getVal()).isEqualTo(STBorder.SINGLE);
            assertThat(value(rule.getSz())).isEqualTo(20);   // восьмые pt: 2,5 pt
            XWPFTableRow top = table(d, "Жауапкершілігі\nшектеулі серіктестігі").getRow(0);
            assertThat(top.getCell(0).getParagraphs().get(0).getAlignment()).isEqualTo(ParagraphAlignment.LEFT);
            assertThat(top.getCell(1).getParagraphs().get(0).getAlignment()).isEqualTo(ParagraphAlignment.RIGHT);
            assertThat(top.getCell(1).getParagraphs().get(0).getRuns().get(0).isBold()).isTrue();
        }
    }

    /** document.xml — по схеме OOXML (проверка XMLBeans): Word откроет файл без «восстановления содержимого». */
    @Test
    void documentXmlIsSchemaValid() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            CompanyProfile p = KpFixtures.profileKz();
            p.setStampPng(KpTestSupport.circlePng());
            p.setSignaturePng(KpTestSupport.signaturePng());
            ClientOffer o = withSectionAndIncluded(KpFixtures.offer2409());
            o.setWithStamp(true);
            switch (variant) {
                case 1 -> {
                    o.setLandscape(true);
                    o.setTermsStyle(TermsStyle.TABLE);
                    p.setLogoPng(ovalPng());
                }
                case 2 -> {
                    o.setSignoff(OfferSignoff.COMPANY);
                    o.setSignoffContacts(true);
                    o.setRecipient("Главному врачу\nГКП «Областная больница»");
                }
                case 3 -> {
                    o.setWithStamp(false);
                    o.setSignoff(OfferSignoff.COMPANY);
                    o.setSubject("Предмет\nв две строки");
                }
                default -> { }
            }
            try (XWPFDocument d = open(docx(o, p))) {
                List<XmlError> errors = new ArrayList<>();
                XmlOptions options = new XmlOptions();
                options.setErrorListener(errors);
                boolean valid = d.getDocument().validate(options);
                assertThat(errors).as("вариант %d", variant).isEmpty();
                assertThat(valid).as("вариант %d", variant).isTrue();
            }
        }
    }

    // --- помощники ---

    /** Плавающая картинка строки подписи (печать или подпись): её якорь, абзац строки подписи с ним и ячейка этого абзаца. */
    private record Floating(CTAnchor anchor, XWPFParagraph paragraph, XWPFTableCell cell) {

        /** {ширина, высота}, мм. */
        double[] extentMm() {
            return new double[] {anchor.getExtent().getCx() / EMU_PER_MM, anchor.getExtent().getCy() / EMU_PER_MM};
        }

        /** Высота абзаца строки подписи (ровно — EXACT), мм. */
        double lineMm() {
            return exactLineMm(paragraph);
        }

        /**
         * Прямоугольник картинки от начала линии (левого края ячейки) и от самой линии (низа ячейки), мм: {левый, верхний,
         * правый, нижний} край. Абзац якоря — единственный в прижатой к низу ячейке таблицы без полей, без отступов и ровно
         * своей высоты: его верх — на эту высоту выше линии.
         */
        double[] boxFromLineMm() {
            assertThat(anchor.getPositionH().getRelativeFrom()).isEqualTo(STRelFromH.COLUMN);
            assertThat(anchor.getPositionV().getRelativeFrom()).isEqualTo(STRelFromV.PARAGRAPH);
            assertThat(cell.getParagraphs()).containsExactly(paragraph);
            assertThat(cell.getVerticalAlignment()).isEqualTo(XWPFTableCell.XWPFVertAlign.BOTTOM);
            CTTblCellMar mar = cell.getTableRow().getTable().getCTTbl().getTblPr().getTblCellMar();
            assertThat(List.of(mar.getTop(), mar.getBottom(), mar.getLeft())).allSatisfy(w -> assertThat(value(w.getW())).isZero());
            assertThat(Math.max(0, paragraph.getSpacingBefore())).isZero();
            assertThat(Math.max(0, paragraph.getSpacingAfter())).isZero();
            double[] size = extentMm();
            double left = anchor.getPositionH().getPosOffset() / EMU_PER_MM;
            double top = anchor.getPositionV().getPosOffset() / EMU_PER_MM - lineMm();
            return new double[] {left, top, left + size[0], top + size[1]};
        }

        /** Центр картинки от начала линии и от самой линии, мм. */
        double[] centreFromLineMm() {
            double[] box = boxFromLineMm();
            return new double[] {(box[0] + box[2]) / 2, (box[1] + box[3]) / 2};
        }
    }

    /** Плавающая картинка с этим именем (docPr name: «Печать», «Подпись»). */
    private static Floating floating(XWPFDocument d, String title) {
        for (XWPFParagraph p : allParagraphs(d)) {
            for (XWPFRun r : p.getRuns()) {
                for (CTDrawing drawing : r.getCTR().getDrawingList()) {
                    for (CTAnchor anchor : drawing.getAnchorList()) {
                        if (title.equals(anchor.getDocPr().getName())) {
                            assertThat(p.getBody()).as(title + " — в ячейке таблицы подписи").isInstanceOf(XWPFTableCell.class);
                            return new Floating(anchor, p, (XWPFTableCell) p.getBody());
                        }
                    }
                }
            }
        }
        throw new AssertionError("в документе нет плавающей картинки «" + title + "»");
    }

    /** Часть пакета .docx как XML (DOM с пространствами имён). */
    private static org.w3c.dom.Document part(byte[] docx, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals(name)) continue;
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                return factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
            }
        }
        throw new AssertionError("в .docx нет части " + name);
    }

    /** Дочерние элементы, по порядку. */
    private static List<Element> elements(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) out.add(e);
        }
        return out;
    }

    /** Высота абзаца, заданная ровно (EXACT), мм. */
    private static double exactLineMm(XWPFParagraph p) {
        CTSpacing spacing = p.getCTP().getPPr().getSpacing();
        assertThat(spacing.getLineRule()).isEqualTo(STLineSpacingRule.EXACT);
        return value(spacing.getLine()) / TWIPS_PER_MM;
    }

    /** Кегль знака абзаца, pt. */
    private static double markPt(XWPFParagraph p) {
        CTParaRPr rPr = p.getCTP().getPPr().getRPr();
        return value(rPr.getSzArray(0).getVal()) / 2.0;
    }

    private static boolean noWrap(XWPFTableCell c) {
        return c.getCTTc().isSetTcPr() && c.getCTTc().getTcPr().isSetNoWrap();
    }

    private static long cellWidth(XWPFTableCell c) {
        return value(c.getCTTc().getTcPr().getTcW().getW());
    }

    /** Первая таблица, в которой есть ячейка ровно с этим текстом. */
    private static XWPFTable table(XWPFDocument d, String cellText) {
        return d.getTables().stream()
                .filter(t -> t.getRows().stream().flatMap(r -> r.getTableCells().stream()).anyMatch(c -> c.getText().equals(cellText)))
                .findFirst().orElseThrow(() -> new AssertionError("нет таблицы с ячейкой «" + cellText + "»"));
    }

    /** Первый абзац (тела или ячейки), который начинается с этого текста. */
    private static XWPFParagraph paragraph(XWPFDocument d, String start) {
        return allParagraphs(d).stream().filter(p -> p.getText().startsWith(start)).findFirst()
                .orElseThrow(() -> new AssertionError("нет абзаца «" + start + "…»"));
    }

    private static long value(Object number) {
        return ((BigInteger) number).longValue();
    }

    private static long twips(double mm) {
        return Math.round(mm * TWIPS_PER_MM);
    }

    /** КП с разделом в начале и строкой «включено в стоимость» в конце — как КП на ИВЛ. */
    private static ClientOffer withSectionAndIncluded(ClientOffer o) {
        ClientOfferItem section = ClientOfferTestData.item(o, 0, "Основные комплектующие:", null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        o.getItems().add(0, section);
        ClientOfferItem included = ClientOfferTestData.item(o, 99, "Гарантийное сервисное обслуживание 37 месяцев", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        o.getItems().add(included);
        return o;
    }

    /** Реквизиты KZ с должностью длиннее — как у сида РФ «Директор ООО «РЕГИОН-МЕД»»: линия начинается на 9 мм правее. */
    private static CompanyProfile profileWithLongTitle() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setShortName("ООО «РЕГИОН-МЕД»");
        return p;
    }

    /** Линия подписи в PDF — нарисованная черта ≈ 45 мм у низа строки должности (как в KpPdfDocumentTest). */
    private static KpTestSupport.Box signLine(byte[] pdf, KpTestSupport.Placed title) {
        List<KpTestSupport.Box> lines = KpTestSupport.shapeBoxes(pdf).stream()
                .filter(b -> b.page() == title.page() && b.height() < 1 && b.width() > 40 && b.width() < 50
                        && Math.abs(b.top() - title.bottom()) < 3)
                .toList();
        assertThat(lines).as("линия подписи").hasSize(1);
        return lines.get(0);
    }

    /** PNG width × height: росчерк — маленькая подпись или логотип, меньше пределов по обеим сторонам. */
    private static byte[] strokePng(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(20, 30, 120));
        g.setStroke(new BasicStroke(3));
        g.drawLine(2, height - 3, width - 3, 2);
        g.dispose();
        return KpTestSupport.png(img);
    }

    /** PNG 200×140: овальная «печать» — не круглая, высота ≠ ширине. */
    private static byte[] ovalPng() {
        BufferedImage img = new BufferedImage(200, 140, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(30, 60, 200));
        g.setStroke(new BasicStroke(8));
        g.drawOval(8, 8, 184, 124);
        g.dispose();
        return KpTestSupport.png(img);
    }
}
