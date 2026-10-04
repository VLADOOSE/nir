package com.vladoose.nir.service.document;

import com.vladoose.nir.service.offer.ColumnAlign;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.xml.namespace.QName;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * KpDocument → Word (.docx) через Apache POI XWPF (спека §6.4): те же блоки, колонки и кегль таблицы, что у PDF, и та же
 * геометрия листа (KpPageGeometry) — поля страницы и поля ячеек как у @page и ячеек шаблона offer.html, поэтому доли
 * колонок значат одно и то же в обоих документах. Ширины — только явные: tblW в DXA + fixed + gridCol + tcW у каждой
 * ячейки («100%» POI не работает — проверено 2026-10-02). Вертикальные отступы блоков — поля CSS шаблона (Flow). Печать
 * и подпись — плавающие картинки «перед текстом» (wp:anchor + wrapNone) в ячейке линии подписи, их можно сдвинуть в Word.
 * Файл объявляет режим Word 2013+ (compatibilityMode 15). Шрифт Times New Roman. ⚠ Quick Look на Mac плавающие картинки
 * рисует не на своём месте и ширин ячеек не соблюдает — вёрстку проверять только в Word.
 */
@Component
public class KpDocxRenderer {

    private static final String FONT = "Times New Roman";
    private static final double TWIPS_PER_MM = 1440 / 25.4;
    private static final long EMU_PER_MM = 36_000, EMU_PER_TWIP = 635;
    /** Картинка без заданного размера — 1 px = 1/96 дюйма (px CSS), как у PDF. */
    private static final double MM_PER_PX = 25.4 / 96;

    // Числа вёрстки — из CSS offer.html (класс — в комментарии у места, где число используется)
    /** Кегли, pt: текст, название вместо логотипа, строки бланка, заголовок, предмет, таблица условий, «Итого», контакты. */
    private static final double BODY_PT = 11, BRAND_PT = 30, LETTERHEAD_PT = 12, TITLE_PT = 13, SUBJECT_PT = 12,
            TERMS_TABLE_PT = 10.5, TOTAL_PT = 12, CONTACTS_PT = 10;
    /** Строки бланка — line-height 1.25 × 12 pt (.lh-lines), твипы. */
    private static final int LETTERHEAD_LINE = 300;
    /** Логотип — не выше, мм (.lh-logo img: max-height). */
    private static final double LOGO_MAX_HEIGHT_MM = 20;
    /** Черта под бланком — 2,5 pt (.lh-rule), восьмые pt. */
    private static final int RULE_EIGHTHS = 20;
    /** Пустая строка в 1 pt, твипы: под чертой бланка и между таблицами, когда отступа между ними нет. */
    private static final int THIN_LINE = 20;
    /** Рамки таблиц позиций и условий — 0,5 pt, линия подписи — 0,75 pt (в PDF 0,7), восьмые pt. */
    private static final int TABLE_BORDER = 4, SIGN_LINE_BORDER = 6;
    /** Подпись условия — 45 % ширины таблицы условий (.term-label). */
    private static final double TERM_LABEL_SHARE = 0.45;
    /** «С уважением,» — на 10 мм ниже блока над ним (.signoff: padding-top, с полем блока над ним не схлопывается), мм. */
    private static final double SIGNOFF_PADDING_MM = 10;
    /** Запас ячеек должности, фамилии и названия компании сверх измеренной строки, мм: Word не перенесёт её из-за округлений. */
    private static final double MEASURE_SLACK_MM = 0.5;
    /** Порядок наложения плавающих картинок (relativeHeight, больше — выше): печать поверх подписи, как в PDF. */
    private static final long Z_SIGNATURE = 251658240, Z_STAMP = 251659264;
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** Отступы колонтитулов, твипы (у Word по умолчанию 1,25 см): в pgMar они обязательны по схеме. */
    private static final int HEADER_FOOTER = 708;

    /** Обычное начертание для замера строк подписи: Liberation Serif — метрика Times New Roman (KpFonts). */
    private static final TrueTypeFont METRICS = metrics();

    public byte[] render(KpDocument doc) {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            defaults(d);
            compatibilityMode(d);
            int width = page(d, doc.landscape());
            Flow flow = new Flow(d);
            letterhead(flow, doc.letterhead(), width);
            meta(flow, doc, width);
            text(flow.paragraph(2, 1), ParagraphAlignment.CENTER, true, TITLE_PT, doc.title(), false);         // .title
            if (doc.subject() != null) {                                                                        // .subject
                text(flow.paragraph(0, 2), ParagraphAlignment.CENTER, true, SUBJECT_PT, doc.subject(), true);
            }
            if (doc.intro() != null) {                                                                          // .intro
                text(flow.paragraph(2, 2), ParagraphAlignment.LEFT, false, BODY_PT, doc.intro(), true);
            }
            if (!doc.termsTable().isEmpty()) termsTable(flow, doc.termsTable(), width);
            itemsTable(flow, doc, width);
            for (int i = 0; i < doc.totalLines().size(); i++) {                                                 // .totals
                boolean main = i == 0;
                text(flow.paragraph(main ? 3 : 0, 0), ParagraphAlignment.RIGHT, main, main ? TOTAL_PT : BODY_PT,
                        doc.totalLines().get(i), false);
            }
            if (doc.amountInWords() != null) {                                                                  // .words
                text(flow.paragraph(2, 0), ParagraphAlignment.LEFT, false, BODY_PT, doc.amountInWords(), false);
            }
            for (int i = 0; i < doc.termsList().size(); i++) {                                                  // .terms-list
                text(flow.paragraph(i == 0 ? 4 : 0, 1), ParagraphAlignment.LEFT, false, BODY_PT, doc.termsList().get(i), false);
            }
            signoff(flow, doc.signoff(), width);
            d.write(out);
            return out.toByteArray();
        } catch (IOException | InvalidFormatException | XmlException e) {
            throw new IllegalStateException("Word не собран", e);
        }
    }

    /**
     * Тело документа сверху вниз. Вертикальные поля блоков — CSS-поля offer.html (сверху, снизу, мм); соседние поля
     * «схлопываются», как в CSS, — между блоками большее из двух, — поэтому у абзаца выставляется только отступ перед ним.
     * Перед таблицей отступ уходит в «после» предыдущего абзаца; две таблицы подряд Word склеил бы в одну — между ними
     * пустой абзац ровно высотой в отступ.
     */
    private static final class Flow {
        private final XWPFDocument d;
        private XWPFParagraph last;     // последний абзац тела, если после него ещё не было таблицы
        private boolean afterTable;     // последний блок — таблица
        private double gapMm;           // нижнее поле последнего блока, ещё не выставленное

        Flow(XWPFDocument d) {
            this.d = d;
        }

        XWPFParagraph paragraph(double topMm, double bottomMm) {
            XWPFParagraph p = d.createParagraph();
            spacing(p, twips(Math.max(gapMm, topMm)), 0);
            last = p;
            afterTable = false;
            gapMm = bottomMm;
            return p;
        }

        XWPFTable table(int rows, int cols, double topMm, double bottomMm) {
            int gap = twips(Math.max(gapMm, topMm));
            if (last != null) {
                last.setSpacingAfter(gap);
            } else if (afterTable) {
                XWPFParagraph spacer = d.createParagraph();
                spacing(spacer, 0, 0);
                exactLine(spacer, Math.max(gap, THIN_LINE));
            }
            XWPFTable t = d.createTable(rows, cols);
            last = null;
            afterTable = true;
            gapMm = bottomMm;
            return t;
        }
    }

    /** Шрифт по умолчанию — Times New Roman 11 pt, как текст PDF: им же пишется то, что допечатают в Word. */
    private static void defaults(XWPFDocument d) {
        CTStyles styles = CTStyles.Factory.newInstance();
        CTRPr rPr = styles.addNewDocDefaults().addNewRPrDefault().addNewRPr();
        CTFonts fonts = rPr.addNewRFonts();
        fonts.setAscii(FONT);
        fonts.setHAnsi(FONT);
        fonts.setCs(FONT);
        fonts.setEastAsia(FONT);
        rPr.addNewSz().setVal(halfPoints(BODY_PT));
        rPr.addNewSzCs().setVal(halfPoints(BODY_PT));
        d.createStyles().setStyles(styles);
    }

    /**
     * Режим Word 2013+ (compatibilityMode 15): без него Word открывает файл «в режиме совместимости» — в русском Office
     * «[Режим ограниченной функциональности]» в заголовке окна, что похоже на ошибку, — и отступ таблиц считает по-старому
     * (layout). CTCompat в poi-ooxml-lite нет — элемент вставляется курсором; settings нового документа POI пуст, поэтому
     * compat — единственный его элемент и порядок элементов CT_Settings не нарушается.
     */
    private static void compatibilityMode(XWPFDocument d) {
        try (XmlCursor c = d.getSettings().getCTSettings().newCursor()) {
            c.toEndToken();
            c.beginElement(new QName(W_NS, "compat", "w"));
            c.beginElement(new QName(W_NS, "compatSetting", "w"));
            c.insertAttributeWithValue(new QName(W_NS, "name", "w"), "compatibilityMode");
            c.insertAttributeWithValue(new QName(W_NS, "uri", "w"), "http://schemas.microsoft.com/office/word");
            c.insertAttributeWithValue(new QName(W_NS, "val", "w"), "15");
        }
    }

    /** Лист и поля — KpPageGeometry, те же, что у @page PDF; возвращает ширину набора, твипы. */
    private static int page(XWPFDocument d, boolean landscape) {
        CTBody body = d.getDocument().getBody();
        CTSectPr sect = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        int width = twips(KpPageGeometry.pageWidthMm(landscape));
        size.setW(BigInteger.valueOf(width));
        size.setH(BigInteger.valueOf(twips(KpPageGeometry.pageHeightMm(landscape))));
        size.setOrient(landscape ? STPageOrientation.LANDSCAPE : STPageOrientation.PORTRAIT);
        KpPageGeometry.Margins m = KpPageGeometry.margins(landscape);
        CTPageMar margin = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        margin.setTop(BigInteger.valueOf(twips(m.top())));
        margin.setRight(BigInteger.valueOf(twips(m.right())));
        margin.setBottom(BigInteger.valueOf(twips(m.bottom())));
        margin.setLeft(BigInteger.valueOf(twips(m.left())));
        margin.setHeader(BigInteger.valueOf(HEADER_FOOTER));
        margin.setFooter(BigInteger.valueOf(HEADER_FOOTER));
        margin.setGutter(BigInteger.ZERO);
        return width - twips(m.left()) - twips(m.right());
    }

    /**
     * Бланк (спека §6.3): шапка в две колонки жирным (.lh-top) → логотип (.lh-logo: не шире набора, не выше 20 мм, без
     * увеличения; margin: 1mm 0) или название крупно (.lh-brand: 30 pt жирным; margin: 1mm 0) → строки бланка по центру
     * жирным (.lh-lines: 12 pt, line-height 1.25) → черта.
     */
    private static void letterhead(Flow flow, KpDocument.Letterhead lh, int width) throws IOException, InvalidFormatException {
        if (!lh.left().isEmpty() || !lh.right().isEmpty()) {
            int[] w = {width / 2, width - width / 2};
            XWPFTable t = flow.table(1, 2, 0, 0);
            layout(t, w, 0, 0);
            borders(t, false);
            XWPFTableRow row = t.getRow(0);
            cellLines(row.getCell(0), lh.left(), ParagraphAlignment.LEFT, true, BODY_PT);
            cellLines(row.getCell(1), lh.right(), ParagraphAlignment.RIGHT, true, BODY_PT);
            valign(row, XWPFTableCell.XWPFVertAlign.TOP);
            cellWidths(row, w);
        }
        if (lh.logoPng() != null) {
            XWPFParagraph p = flow.paragraph(1, 1);
            p.setAlignment(ParagraphAlignment.CENTER);
            picture(run(p, false, BODY_PT), lh.logoPng(), "logo.png", fit(lh.logoPng(), width / TWIPS_PER_MM, LOGO_MAX_HEIGHT_MM));
        } else if (lh.brandText() != null) {
            text(flow.paragraph(1, 1), ParagraphAlignment.CENTER, true, BRAND_PT, lh.brandText(), false);
        }
        if (!lh.lines().isEmpty()) {
            XWPFParagraph p = flow.paragraph(0, 0);
            p.setAlignment(ParagraphAlignment.CENTER);
            markSize(p, LETTERHEAD_PT);
            lines(p, lh.lines(), true, LETTERHEAD_PT);
            exactLine(p, LETTERHEAD_LINE);
        }
        rule(flow);
    }

    /** Черта под бланком (.lh-rule: border-bottom 2.5pt; margin: 2mm 0 4mm 0) — пустая строка в 1 pt с нижней рамкой. */
    private static void rule(Flow flow) {
        XWPFParagraph p = flow.paragraph(2, 4);
        exactLine(p, THIN_LINE);
        CTPPr pPr = ppr(p);
        line((pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr()).addNewBottom(), RULE_EIGHTHS);
    }

    /** «Исх. №» слева, «Кому» справа (.meta: две половины без полей ячеек; margin-bottom: 3mm). */
    private static void meta(Flow flow, KpDocument doc, int width) {
        int[] w = {width / 2, width - width / 2};
        XWPFTable t = flow.table(1, 2, 0, 3);
        layout(t, w, 0, 0);
        borders(t, false);
        XWPFTableRow row = t.getRow(0);
        cellLines(row.getCell(0), List.of(doc.numberLine()), ParagraphAlignment.LEFT, false, BODY_PT);
        cellLines(row.getCell(1), doc.recipientLines(), ParagraphAlignment.RIGHT, false, BODY_PT);
        valign(row, XWPFTableCell.XWPFVertAlign.TOP);
        cellWidths(row, w);
    }

    /** Условия таблицей (.terms-table: подпись 45 % жирным, 10,5 pt, рамки 0,5 pt, поля ячеек — общие; margin: 2mm 0 4mm 0). */
    private static void termsTable(Flow flow, List<KpDocument.Term> terms, int width) {
        int label = (int) Math.round(width * TERM_LABEL_SHARE);
        int[] w = {label, width - label};
        XWPFTable t = flow.table(terms.size(), 2, 2, 4);
        layout(t, w, twips(KpPageGeometry.CELL_PADDING_H_MM), twips(KpPageGeometry.CELL_PADDING_V_MM));
        borders(t, true);
        for (int i = 0; i < terms.size(); i++) {
            XWPFTableRow row = t.getRow(i);
            cellLines(row.getCell(0), List.of(terms.get(i).label()), ParagraphAlignment.LEFT, true, TERMS_TABLE_PT);
            cellLines(row.getCell(1), terms.get(i).valueLines(), ParagraphAlignment.LEFT, false, TERMS_TABLE_PT);
            valign(row, XWPFTableCell.XWPFVertAlign.TOP);
            cellWidths(row, w);
        }
    }

    /**
     * Таблица позиций (.items; margin-top: 2mm): колонки долями модели, кегль модели (KpDocument.tableFontPt) у всего в ней,
     * шапка жирным по центру и повторяется на каждой странице, строки не рвутся между страницами (page-break-inside:
     * avoid), раздел и «включено в стоимость» — объединённой ячейкой (gridSpan), ячейки неразрывных колонок — w:noWrap.
     */
    private static void itemsTable(Flow flow, KpDocument doc, int width) {
        List<KpDocument.Column> cols = doc.columns();
        int n = cols.size();
        int[] w = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            w[i] = i == n - 1 ? width - used : (int) Math.round(width * cols.get(i).percent() / 100.0);
            used += w[i];
        }
        double pt = doc.tableFontPt();
        XWPFTable t = flow.table(1, n, 2, 0);
        layout(t, w, twips(KpPageGeometry.CELL_PADDING_H_MM), twips(KpPageGeometry.CELL_PADDING_V_MM));
        borders(t, true);
        XWPFTableRow header = t.getRow(0);
        header.setRepeatHeader(true);
        header.setCantSplitRow(true);
        for (int i = 0; i < n; i++) cellLines(header.getCell(i), List.of(cols.get(i).label()), ParagraphAlignment.CENTER, true, pt);
        valign(header, XWPFTableCell.XWPFVertAlign.CENTER);
        cellWidths(header, w);
        for (KpDocument.Row r : doc.rows()) {
            XWPFTableRow row = t.createRow();
            row.setCantSplitRow(true);
            boolean section = r.kind() == KpDocument.RowKind.SECTION;
            for (int i = 0; i < r.cells().size(); i++) {
                XWPFTableCell cell = row.getCell(i);
                cellLines(cell, r.cells().get(i), align(cols.get(i).align()), section, pt);
                if (cols.get(i).nowrap()) tcPr(cell).addNewNoWrap();   // .items td.nowrap { white-space: nowrap }
            }
            if (r.spanFrom() < n) {   // .span-cell: по центру, у раздела — слева
                for (int i = n - 1; i > r.spanFrom(); i--) row.removeCell(i);
                XWPFTableCell span = row.getCell(r.spanFrom());
                tcPr(span).addNewGridSpan().setVal(BigInteger.valueOf(n - r.spanFrom()));
                cellLines(span, r.spanLines(), section ? ParagraphAlignment.LEFT : ParagraphAlignment.CENTER, section, pt);
            }
            valign(row, XWPFTableCell.XWPFVertAlign.CENTER);
            cellWidths(row, w);
        }
    }

    /**
     * Подпись (спека §6.3, .signoff): «С уважением,» на 10 мм ниже блока над ним, затем «Должность ____ Фамилия» или
     * название компании, контакты — строкой ниже. Блок, как в PDF (page-break-inside: avoid), не отрывается от своего
     * начала на другую страницу — «не отрывать от следующего».
     */
    private static void signoff(Flow flow, KpDocument.Signoff s, int width) throws IOException, InvalidFormatException, XmlException {
        XWPFParagraph regards = flow.paragraph(0, 0);
        regards.setSpacingBefore(regards.getSpacingBefore() + twips(SIGNOFF_PADDING_MM));
        text(regards, ParagraphAlignment.LEFT, false, BODY_PT, "С уважением,", false);
        regards.setKeepNext(true);
        List<XWPFParagraph> sign;
        if (s.director()) {
            sign = directorSign(flow, s, width);
        } else if (s.stampPng() != null) {
            sign = companySign(flow, s, width);
        } else {
            sign = List.of(text(flow.paragraph(0, 0), ParagraphAlignment.LEFT, false, BODY_PT, s.titleLine(), false));
        }
        if (s.contacts() != null) {                                                                             // .contacts
            sign.forEach(p -> p.setKeepNext(true));
            text(flow.paragraph(2, 0), ParagraphAlignment.LEFT, false, CONTACTS_PT, s.contacts(), false);
        }
    }

    /**
     * «Должность ____ Фамилия» (.sign-table — по содержимому, margin-top: 1mm; поля ячеек — нули): должность — ячейка по
     * ширине своей строки, поэтому линия начинается сразу за ней; линия — нижняя рамка ячейки в 45 мм; фамилия — в 3 мм
     * после линии; ячейки прижаты к низу. В ячейке линии — подпись и печать, обе плавающие (signParagraph).
     */
    private static List<XWPFParagraph> directorSign(Flow flow, KpDocument.Signoff s, int width)
            throws IOException, InvalidFormatException, XmlException {
        int lineWidth = twips(KpPageGeometry.SIGN_LINE_WIDTH_MM), gap = twips(KpPageGeometry.SIGN_NAME_GAP_MM);
        int name = gap + measure(s.nameLine(), BODY_PT);
        int title = Math.min(measure(s.titleLine(), BODY_PT), Math.max(width - lineWidth - name, width / 4));
        int[] w = {title, lineWidth, name};
        XWPFTable t = flow.table(1, 3, 1, 0);
        layout(t, w, 0, 0);
        borders(t, false);
        XWPFTableRow row = t.getRow(0);
        row.setCantSplitRow(true);
        cellLines(row.getCell(0), List.of(s.titleLine()), ParagraphAlignment.LEFT, false, BODY_PT);
        XWPFTableCell lineCell = row.getCell(1);
        CTTcPr pr = tcPr(lineCell);
        line((pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders()).addNewBottom(), SIGN_LINE_BORDER);
        XWPFParagraph sign = signParagraph(lineCell);
        if (s.signaturePng() != null) signature(sign, s.signaturePng(), lineWidth);
        if (s.stampPng() != null) stamp(sign, s.stampPng(), s.stampSizeMm());
        XWPFTableCell nameCell = row.getCell(2);
        cellLines(nameCell, s.nameLine() == null ? List.of() : List.of(s.nameLine()), ParagraphAlignment.LEFT, false, BODY_PT);
        nameCell.getParagraphs().get(0).setIndentationLeft(gap);   // .sign-table .sign-name { padding-left: 3mm }
        valign(row, XWPFTableCell.XWPFVertAlign.BOTTOM);
        cellWidths(row, w);
        return paragraphs(row);
    }

    /**
     * Подпись от компании с печатью (.company-sign; поля ячеек — нули): название — ячейка по ширине своей строки, за ним —
     * пустая ячейка строки подписи; от её левого края (конца названия) печать отсчитывается так же, как от начала линии
     * у директора, а название стоит внизу строки, как должность.
     */
    private static List<XWPFParagraph> companySign(Flow flow, KpDocument.Signoff s, int width)
            throws IOException, InvalidFormatException, XmlException {
        int box = twips(KpPageGeometry.SIGN_LINE_WIDTH_MM);
        int name = Math.min(measure(s.titleLine(), BODY_PT), Math.max(width - box, width / 4));
        int[] w = {name, box};
        XWPFTable t = flow.table(1, 2, 0, 0);
        layout(t, w, 0, 0);
        borders(t, false);
        XWPFTableRow row = t.getRow(0);
        row.setCantSplitRow(true);
        cellLines(row.getCell(0), List.of(s.titleLine()), ParagraphAlignment.LEFT, false, BODY_PT);
        stamp(signParagraph(row.getCell(1)), s.stampPng(), s.stampSizeMm());
        valign(row, XWPFTableCell.XWPFVertAlign.BOTTOM);
        cellWidths(row, w);
        return paragraphs(row);
    }

    /**
     * Абзац строки подписи — единственный в ячейке без полей, прижатой к низу, без отступов и ровно SIGN_HEIGHT_MM
     * высотой (как .sign-box в PDF): его низ — линия (низ ячейки), верх — на SIGN_HEIGHT_MM выше. Видимого в нём нет —
     * только якоря плавающих подписи и печати, которые отсчитываются от верха абзаца; высоту строки подписи держит сам абзац.
     */
    private static XWPFParagraph signParagraph(XWPFTableCell cell) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        spacing(p, 0, 0);
        exactLine(p, twips(KpPageGeometry.SIGN_HEIGHT_MM));
        markSize(p, BODY_PT);
        return p;
    }

    /**
     * Подпись (.sign-pic: left 0; right 0; bottom 0; text-align: center — по центру линии, нижним краем на линии; img — не
     * больше 44 × 15 мм, без увеличения) — плавающая картинка в абзаце строки подписи. Положение по горизонтали — от левого
     * края ячейки линии без полей (positionH column): (ширина ячейки − w) / 2 — по центру линии; по вертикали — от верха
     * абзаца строки подписи, а он на высоту абзаца L выше линии (signParagraph): L − h — нижний край ровно на линии.
     * Встроенной её не сделать: в строке точной высоты Word ставит картинку на базовую линию — на 80 % высоты строки
     * (20 % под ней — под «хвосты» букв), и подпись висела на 3 мм выше линии (замер в Word 16.80).
     */
    private static void signature(XWPFParagraph sign, byte[] png, int cellTwips) throws IOException, InvalidFormatException, XmlException {
        long[] size = fit(png, KpPageGeometry.SIGNATURE_MAX_WIDTH_MM, KpPageGeometry.SIGN_HEIGHT_MM);
        long left = (cellTwips * EMU_PER_TWIP - size[0]) / 2;
        long top = lineEmu(sign) - size[1];
        floating(sign, png, "signature.png", "Подпись", size[0], size[1], left, top, Z_SIGNATURE);
    }

    /**
     * Печать (спека §6.3) — плавающая картинка в абзаце строки подписи, поверх подписи. Как в PDF (KpHtmlRenderer: width S;
     * left X − S/2; bottom −(Y + S/2) от линии): ширина — размер печати S, высота h — по пропорциям картинки; левый край —
     * на X − S/2 от начала линии (positionH от колонки — левого края ячейки без полей); нижний край — на Y + S/2 ниже линии,
     * то есть верхний — на Y + S/2 − h ниже линии. У круглой печати (h = S) центр — ровно на X правее начала линии и на Y
     * ниже неё (Y = −4: на 4 мм выше). positionV отсчитывается от верха абзаца строки подписи, а он на высоту абзаца L выше
     * линии (signParagraph): смещение = (Y + S/2 − h) + L.
     */
    private static void stamp(XWPFParagraph sign, byte[] png, int sizeMm) throws IOException, InvalidFormatException, XmlException {
        long width = sizeMm * EMU_PER_MM;
        long height = Math.round(width * aspect(png));
        long left = Math.round((KpPageGeometry.STAMP_CENTER_X_MM - sizeMm / 2.0) * EMU_PER_MM);
        long top = Math.round((KpPageGeometry.STAMP_CENTER_Y_MM + sizeMm / 2.0) * EMU_PER_MM) - height + lineEmu(sign);
        floating(sign, png, "stamp.png", "Печать", width, height, left, top, Z_STAMP);
    }

    /** Высота абзаца строки подписи (ровно), EMU: от его верха отсчитываются плавающие картинки. */
    private static long lineEmu(XWPFParagraph sign) {
        return ((BigInteger) ppr(sign).getSpacing().getLine()).longValue() * EMU_PER_TWIP;
    }

    /**
     * Картинка «перед текстом» (wp:anchor, wrapNone, layoutInCell, allowOverlap; её можно сдвинуть в Word) в абзаце строки
     * подписи: left — от левого края ячейки (positionH column; у ячейки нет полей), top — от верха абзаца (positionV
     * paragraph), EMU; z — порядок наложения (relativeHeight). POI вставляет картинку встроенной — так выдаются связь с
     * файлом картинки и уникальный id (docPr), — затем она становится плавающей с тем же id.
     */
    private static void floating(XWPFParagraph sign, byte[] png, String file, String title, long width, long height,
                                 long left, long top, long z) throws IOException, InvalidFormatException, XmlException {
        XWPFRun r = run(sign, false, BODY_PT);
        r.addPicture(new ByteArrayInputStream(png), PictureType.PNG, file, (int) width, (int) height);
        CTDrawing drawing = r.getCTR().getDrawingArray(0);
        CTInline inline = drawing.getInlineArray(0);
        // разбор — как CTDrawing: CTAnchor.Factory.parse сделал бы корневой wp:anchor содержимым — вложенный anchor, битый файл
        CTAnchor anchor = CTDrawing.Factory.parse(anchorXml(left, top, width, height, z, inline.getDocPr().getId(), title))
                .getAnchorArray(0);
        anchor.setGraphic(inline.getGraphic());
        drawing.removeInline(0);
        drawing.setAnchorArray(new CTAnchor[] {anchor});
    }

    private static String anchorXml(long left, long top, long width, long height, long z, long id, String title) {
        return "<wp:anchor xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                + " distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"" + z + "\""
                + " behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"column\"><wp:posOffset>" + left + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>" + top + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + width + "\" cy=\"" + height + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"" + id + "\" name=\"" + title + "\"/><wp:cNvGraphicFramePr/></wp:anchor>";
    }

    private static void picture(XWPFRun r, byte[] png, String name, long[] size) throws IOException, InvalidFormatException {
        r.addPicture(new ByteArrayInputStream(png), PictureType.PNG, name, (int) size[0], (int) size[1]);
    }

    /**
     * Размер картинки, как у PDF при max-width / max-height (CSS 2.1 §10.4): натуральный (1 px = 1/96 дюйма), вписанный в
     * maxWidthMm × maxHeightMm без увеличения → {ширина, высота} в EMU.
     */
    private static long[] fit(byte[] png, double maxWidthMm, double maxHeightMm) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        double w = img == null ? maxHeightMm : img.getWidth() * MM_PER_PX;
        double h = img == null ? maxHeightMm : img.getHeight() * MM_PER_PX;
        double scale = Math.min(1, Math.min(maxWidthMm / w, maxHeightMm / h));
        return new long[] {Math.round(w * scale * EMU_PER_MM), Math.round(h * scale * EMU_PER_MM)};
    }

    /** Высота картинки к ширине; нечитаемая — квадрат. */
    private static double aspect(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        return img == null || img.getWidth() == 0 ? 1 : (double) img.getHeight() / img.getWidth();
    }

    /**
     * Ширина таблицы и колонок — явная (tblW в DXA + fixed + gridCol), поля ячеек — явные, ноль тоже (иначе Word берёт свои
     * 108 twip). tblInd = 0: в режиме Word 2013+ (compatibilityMode) отступ отсчитывается до рамки таблицы — рамка стоит на
     * поле листа, как в PDF (в старом режиме Word выносил бы её влево на поле первой ячейки).
     */
    private static void layout(XWPFTable t, int[] widths, int paddingH, int paddingV) {
        CTTbl tbl = t.getCTTbl();
        CTTblPr pr = tbl.getTblPr() != null ? tbl.getTblPr() : tbl.addNewTblPr();
        dxa(pr.isSetTblW() ? pr.getTblW() : pr.addNewTblW(), Arrays.stream(widths).sum());
        dxa(pr.isSetTblInd() ? pr.getTblInd() : pr.addNewTblInd(), 0);
        (pr.isSetTblLayout() ? pr.getTblLayout() : pr.addNewTblLayout()).setType(STTblLayoutType.FIXED);
        CTTblCellMar mar = pr.isSetTblCellMar() ? pr.getTblCellMar() : pr.addNewTblCellMar();
        dxa(mar.isSetTop() ? mar.getTop() : mar.addNewTop(), paddingV);
        dxa(mar.isSetLeft() ? mar.getLeft() : mar.addNewLeft(), paddingH);
        dxa(mar.isSetBottom() ? mar.getBottom() : mar.addNewBottom(), paddingV);
        dxa(mar.isSetRight() ? mar.getRight() : mar.addNewRight(), paddingH);
        CTTblGrid grid = tbl.getTblGrid() != null ? tbl.getTblGrid() : tbl.addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int x : widths) grid.addNewGridCol().setW(BigInteger.valueOf(x));
    }

    /** tcW у каждой ячейки с учётом объединения — без него Word ширины не соблюдает. */
    private static void cellWidths(XWPFTableRow row, int[] widths) {
        int col = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            CTTcPr pr = tcPr(cell);
            int span = pr.isSetGridSpan() ? pr.getGridSpan().getVal().intValue() : 1;
            int w = 0;
            for (int k = col; k < col + span && k < widths.length; k++) w += widths[k];
            dxa(pr.isSetTcW() ? pr.getTcW() : pr.addNewTcW(), w);
            col += span;
        }
    }

    private static void borders(XWPFTable t, boolean lines) {
        XWPFTable.XWPFBorderType type = lines ? XWPFTable.XWPFBorderType.SINGLE : XWPFTable.XWPFBorderType.NONE;
        int size = lines ? TABLE_BORDER : 0;
        String color = lines ? "000000" : "auto";
        t.setTopBorder(type, size, 0, color);
        t.setBottomBorder(type, size, 0, color);
        t.setLeftBorder(type, size, 0, color);
        t.setRightBorder(type, size, 0, color);
        t.setInsideHBorder(type, size, 0, color);
        t.setInsideVBorder(type, size, 0, color);
    }

    /** Сплошная чёрная черта толщиной eighths восьмых pt. */
    private static void line(CTBorder border, int eighths) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(eighths));
        border.setSpace(BigInteger.ZERO);
        border.setColor("000000");
    }

    private static void valign(XWPFTableRow row, XWPFTableCell.XWPFVertAlign align) {
        for (XWPFTableCell c : row.getTableCells()) c.setVerticalAlignment(align);
    }

    private static List<XWPFParagraph> paragraphs(XWPFTableRow row) {
        return row.getTableCells().stream().flatMap(c -> c.getParagraphs().stream()).toList();
    }

    private static void cellLines(XWPFTableCell cell, List<String> lines, ParagraphAlignment align, boolean bold, double pt) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setAlignment(align);
        spacing(p, 0, 0);
        markSize(p, pt);
        lines(p, lines, bold, pt);
    }

    /** Строки — одним прогоном через переносы строки (w:br). */
    private static void lines(XWPFParagraph p, List<String> lines, boolean bold, double pt) {
        if (lines.isEmpty()) return;
        XWPFRun r = run(p, bold, pt);
        for (int i = 0; i < lines.size(); i++) {
            r.setText(lines.get(i));
            if (i < lines.size() - 1) r.addBreak();
        }
    }

    /**
     * Абзац текста. keepBreaks — переводы строк оператора остаются переносами (предмет и вводная — white-space: pre-line
     * в PDF); иначе они — пробелы, как в HTML.
     */
    private static XWPFParagraph text(XWPFParagraph p, ParagraphAlignment align, boolean bold, double pt, String text, boolean keepBreaks) {
        p.setAlignment(align);
        markSize(p, pt);
        lines(p, keepBreaks ? List.of(text.split("\\R")) : List.of(text.replaceAll("\\s*\\R\\s*", " ")), bold, pt);
        return p;
    }

    private static XWPFRun run(XWPFParagraph p, boolean bold, double pt) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(pt);
        r.setComplexScriptFontSize(pt);
        if (bold) r.setBold(true);
        return r;
    }

    /** Кегль знака абзаца: по нему Word считает высоту последней строки — иначе строка ячейки 8 pt была бы высотой в 11 pt. */
    private static void markSize(XWPFParagraph p, double pt) {
        CTPPr pPr = ppr(p);
        CTParaRPr rPr = pPr.isSetRPr() ? pPr.getRPr() : pPr.addNewRPr();
        BigInteger size = halfPoints(pt);
        (rPr.sizeOfSzArray() > 0 ? rPr.getSzArray(0) : rPr.addNewSz()).setVal(size);
        (rPr.sizeOfSzCsArray() > 0 ? rPr.getSzCsArray(0) : rPr.addNewSzCs()).setVal(size);
    }

    private static void spacing(XWPFParagraph p, int beforeTwips, int afterTwips) {
        p.setSpacingBefore(beforeTwips);
        p.setSpacingAfter(afterTwips);
    }

    /** Высота строк абзаца — ровно столько твипов. */
    private static void exactLine(XWPFParagraph p, int twips) {
        CTPPr pPr = ppr(p);
        CTSpacing spacing = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(twips));
        spacing.setLineRule(STLineSpacingRule.EXACT);
    }

    private static CTPPr ppr(XWPFParagraph p) {
        return p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
    }

    private static CTTcPr tcPr(XWPFTableCell cell) {
        return cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
    }

    private static void dxa(CTTblWidth width, int twips) {
        width.setType(STTblWidth.DXA);
        width.setW(BigInteger.valueOf(twips));
    }

    private static int twips(double mm) {
        return (int) Math.round(mm * TWIPS_PER_MM);
    }

    private static BigInteger halfPoints(double pt) {
        return BigInteger.valueOf(Math.round(pt * 2));
    }

    /**
     * Ширина строки обычным начертанием кеглем pt, твипы, с запасом MEASURE_SLACK_MM; пустая — 0. Меряется по метрике
     * Liberation Serif — она та же, что у Times New Roman, которым Word набирает строку (KpFonts).
     */
    private static int measure(String text, double pt) {
        if (text == null || text.isEmpty()) return 0;
        try {
            synchronized (METRICS) {   // таблицы шрифта fontbox читает лениво
                CmapLookup cmap = METRICS.getUnicodeCmapLookup();
                long units = 0;
                for (int cp : text.codePoints().toArray()) units += METRICS.getAdvanceWidth(cmap.getGlyphId(cp));
                return (int) Math.ceil(units * pt * 20 / METRICS.getUnitsPerEm()) + twips(MEASURE_SLACK_MM);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не измерена строка подписи", e);
        }
    }

    private static TrueTypeFont metrics() {
        try {
            return new TTFParser().parse(new RandomAccessReadBuffer(KpFonts.load(KpFonts.REGULAR_FILE)));
        } catch (IOException e) {
            throw new UncheckedIOException("Не прочитан шрифт для замера строк подписи", e);
        }
    }

    private static ParagraphAlignment align(ColumnAlign a) {
        return switch (a) {
            case LEFT -> ParagraphAlignment.LEFT;
            case CENTER -> ParagraphAlignment.CENTER;
            case RIGHT -> ParagraphAlignment.RIGHT;
        };
    }
}
