package com.vladoose.nir.pricelist;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Синтетические прайсы для тестов (настоящие — только локально, PRICE_LIST_CORPUS). */
public final class PriceListFiles {

    private PriceListFiles() {}

    /**
     * Excel как у Аксиона: заголовок над таблицей, две цены (оптовая и дилерская), колонка РУ (объединённая на две строки),
     * разделы объединёнными строками, условия и срок под таблицей.
     */
    public static byte[] axionLikeXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet s = wb.createSheet("Прайс");
            row(s, 0, "Прайс-лист ООО «ZZ Завод»");
            row(s, 2, "№", "Наименование", "Оптовая цена, RUB, без НДС (FCA г. Ижевск)",
                    "Цена со скидкой при подписании дилерского контракта, RUB без НДС", "Разрешительные документы в Казахстане");
            row(s, 3, "Реанимация");
            s.addMergedRegion(new CellRangeAddress(3, 3, 0, 4));
            row(s, 4, "1", "Транспортный ИВЛ А-ИВЛ-Э-03", "330 400", "251 100", "РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК-МТ-5№021045");
            row(s, 5, "2", "Дефибриллятор ДКИ-Н-12. Базовые функции дефибриллятора", "205 000", "150 000",
                    "РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК МИ (МТ)-0№024573 (бессрочно)");
            row(s, 6, "3", "Дефибриллятор ДКИ-Н-12 (без сумки)", "195 000", "140 000");
            s.addMergedRegion(new CellRangeAddress(5, 6, 4, 4));
            row(s, 7, "Хирургия");
            s.addMergedRegion(new CellRangeAddress(7, 7, 0, 4));
            row(s, 8, "4", "Отсасыватель медицинский портативный ОМП-5/80-01", "66 800", "60 100", "Нет РУ в Казахстане");
            row(s, 10, "• Цены указаны без учёта стоимости доставки (FCA г. Ижевск);");
            row(s, 11, "• Предоплата 100%;");
            row(s, 12, "• Указанный прайс-лист актуален с 01 сентября 2026 года по 31 декабря 2026 года.");
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Word как у Орион-Си: без таблицы, строки «наименование ……… цена руб.», розничные цены, раздел с двоеточием. */
    public static byte[] orionLikeDocx() throws Exception {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : List.of("ООО «ZZ-Си»", "РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "действительны с1 ноября 2025 г",
                    "СТЕРИЛИЗАТОРЫ ОЗОНОВЫЕ «ZZ» - МНОГОФУНКЦИОНАЛЬНЫЕ:",
                    "  1.  Стерилизатор озоновый «ZZ» СК – 16 л ……………………...…..……....      957 000 руб.",
                    "  2.  Стерилизатор озоновый «ZZ» СК – 36 л ……………….………….……...   1 097 000 руб.",
                    "    Стерилизатор озоновый «ZZ» без камеры (генератор озона)………………..   475 000 руб.",
                    "Гарантийный срок на все оборудование, выпускаемое ООО «ZZ-Си», составляет 2 года.")) {
                d.createParagraph().createRun().setText(line);
            }
            d.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Длинный прайс: 55 строк с ценой, затем две комплектации с общей (объединённой по вертикали) ячейкой РУ — ниже
     * первых 40 строк листа, где объединённые ячейки разворачиваются и без прайса (шапка).
     */
    public static byte[] longWithMergedRegXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet s = wb.createSheet("Прайс");
            row(s, 0, "№", "Наименование", "Цена, руб.", "Регистрационное удостоверение");
            for (int i = 1; i <= 55; i++) row(s, i, String.valueOf(i), "ZZ Изделие " + i, String.valueOf(1000 + i), "РК МИ (МТ)-0№0" + (10000 + i));
            row(s, 56, "56", "ZZ Монитор М-10 (базовый)", "50 000", "РК МИ (МТ)-0№099999");
            row(s, 57, "57", "ZZ Монитор М-10 (расширенный)", "60 000");
            s.addMergedRegion(new CellRangeAddress(56, 57, 3, 3));
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * PDF как у Аксиона (спека §3): сетка линиями, шапка — ячейками в несколько строк, раздел — объединённой строкой
     * (внутренних вертикалей в его полосе нет), РУ — одна ячейка на две строки (горизонтали в колонке РУ нет), на второй
     * странице — своя сетка без шапки, под таблицей — условия и срок. Координаты — «сверху», как у PDF-текста.
     */
    public static byte[] axionLikePdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             java.io.InputStream fontIn = PriceListFiles.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            org.apache.pdfbox.pdmodel.font.PDType0Font f = org.apache.pdfbox.pdmodel.font.PDType0Font.load(d, fontIn);
            org.apache.pdfbox.pdmodel.PDPage p1 = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            d.addPage(p1);
            float h = p1.getMediaBox().getHeight();
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p1)) {
                cs.setNonStrokingColor(0.92f);                         // фон шапки — толстая заливка с отступом 2 pt от сетки:
                cs.addRect(52, h - 138, 486, 36);                      // не линия, её края границами колонок не становятся
                cs.fill();
                cs.setNonStrokingColor(0f);
                text(cs, f, h, 200, 80, "Прайс-лист ООО «ZZ Завод»");
                float[] xs = {50, 70, 120, 300, 350, 400, 540};
                for (float y : new float[]{100, 140, 152, 175, 235}) hline(cs, h, 50, 540, y);
                hline(cs, h, 50, 400, 205);                            // в колонке РУ линии нет — ячейка на две строки
                vline(cs, h, 50, 100, 235);
                vline(cs, h, 540, 100, 235);
                for (int i = 1; i < xs.length - 1; i++) {               // в полосе раздела 140–152 внутренних линий нет
                    vline(cs, h, xs[i], 100, 140);
                    vline(cs, h, xs[i], 152, 235);
                }
                text(cs, f, h, 55, 122, "№");
                text(cs, f, h, 80, 122, "Фото");
                text(cs, f, h, 180, 122, "Наименование");
                text(cs, f, h, 303, 116, "Оптовая цена,");
                text(cs, f, h, 303, 124, "RUB");
                text(cs, f, h, 353, 116, "Цена дилера,");
                text(cs, f, h, 353, 124, "RUB");
                text(cs, f, h, 405, 122, "Разрешительные документы в Казахстане");
                text(cs, f, h, 190, 148, "Реанимация");
                text(cs, f, h, 57, 166, "1");
                text(cs, f, h, 125, 166, "Транспортный ИВЛ А-ИВЛ-Э-03");
                text(cs, f, h, 310, 166, "330 400");
                text(cs, f, h, 360, 166, "251 100");
                text(cs, f, h, 405, 166, "РУ РК-МТ-5№021045");
                text(cs, f, h, 57, 190, "2");
                text(cs, f, h, 125, 186, "Дефибриллятор ДКИ-Н-12.");
                text(cs, f, h, 125, 194, "Базовые функции");
                text(cs, f, h, 310, 190, "205 000");
                text(cs, f, h, 360, 190, "150 000");
                text(cs, f, h, 405, 202, "РУ РК МИ (МТ)-");
                text(cs, f, h, 405, 210, "0№024573 (бессрочно)");
                text(cs, f, h, 57, 222, "3");
                text(cs, f, h, 125, 222, "Дефибриллятор ДКИ-Н-12 (без сумки)");
                text(cs, f, h, 310, 222, "195 000");
                text(cs, f, h, 360, 222, "140 000");
            }
            org.apache.pdfbox.pdmodel.PDPage p2 = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            d.addPage(p2);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p2)) {
                float[] xs = {30, 45, 100, 290, 345, 395, 560};
                hline(cs, h, 30, 560, 60);
                hline(cs, h, 30, 560, 85);
                for (float x : xs) vline(cs, h, x, 60, 85);
                text(cs, f, h, 35, 74, "4");
                text(cs, f, h, 105, 74, "Отсасыватель портативный ОМП-5/80-01");
                text(cs, f, h, 300, 74, "66 800");
                text(cs, f, h, 355, 74, "60 100");
                text(cs, f, h, 400, 74, "Нет РУ в Казахстане");
                text(cs, f, h, 105, 110, "• Предоплата 100%;");
                text(cs, f, h, 105, 120, "• Указанный прайс-лист актуален с 01 сентября 2026 года по 31 декабря 2026 года.");
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    private static void text(org.apache.pdfbox.pdmodel.PDPageContentStream cs, org.apache.pdfbox.pdmodel.font.PDType0Font f,
                             float pageHeight, float x, float yTop, String s) throws java.io.IOException {
        cs.beginText();
        cs.setFont(f, 6);
        cs.newLineAtOffset(x, pageHeight - yTop);
        cs.showText(s);
        cs.endText();
    }

    private static void hline(org.apache.pdfbox.pdmodel.PDPageContentStream cs, float pageHeight, float x1, float x2, float yTop)
            throws java.io.IOException {
        cs.moveTo(x1, pageHeight - yTop);
        cs.lineTo(x2, pageHeight - yTop);
        cs.stroke();
    }

    private static void vline(org.apache.pdfbox.pdmodel.PDPageContentStream cs, float pageHeight, float x, float y1Top, float y2Top)
            throws java.io.IOException {
        cs.moveTo(x, pageHeight - y1Top);
        cs.lineTo(x, pageHeight - y2Top);
        cs.stroke();
    }

    private static void row(XSSFSheet s, int r, String... cells) {
        XSSFRow row = s.createRow(r);
        for (int c = 0; c < cells.length; c++) row.createCell(c).setCellValue(cells[c]);
    }
}
