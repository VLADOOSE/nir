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

    private static void row(XSSFSheet s, int r, String... cells) {
        XSSFRow row = s.createRow(r);
        for (int c = 0; c < cells.length; c++) row.createCell(c).setCellValue(cells[c]);
    }
}
