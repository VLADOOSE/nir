package com.vladoose.nir.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Шапка ООО «Регион-Мед» для Excel-листа. Занимает первые 7 строк, мёрджит ячейки A:E.
 *
 * Возвращает индекс первой "свободной" строки — продолжать запись данных нужно с неё.
 */
public final class ExcelCompanyHeader {

    private ExcelCompanyHeader() {}

    /**
     * Пишет шапку с реквизитами активного рынка.
     * Вызывается из Spring-сервисов, передающих {@code provider.current()}.
     */
    public static int writeTo(Sheet sheet, Workbook wb, CompanyInfoProvider.Company company) {
        return writeToInternal(sheet, wb,
                company.fullName(),
                company.addressLine1() + " " + company.addressLine2(),
                CompanyInfo.INN + " / " + CompanyInfo.KPP,
                CompanyInfo.OGRN + " / " + CompanyInfo.OKVED,
                CompanyInfo.BANK_NAME + ", БИК " + CompanyInfo.BANK_BIK,
                CompanyInfo.BANK_ACCOUNT,
                CompanyInfo.PHONE + ", " + CompanyInfo.EMAIL);
    }

    /** Обратная совместимость — использует статичные RF-реквизиты. */
    public static int writeTo(Sheet sheet, Workbook wb) {
        return writeToInternal(sheet, wb,
                CompanyInfo.FULL_NAME,
                CompanyInfo.ADDRESS_LINE_1 + " " + CompanyInfo.ADDRESS_LINE_2,
                CompanyInfo.INN + " / " + CompanyInfo.KPP,
                CompanyInfo.OGRN + " / " + CompanyInfo.OKVED,
                CompanyInfo.BANK_NAME + ", БИК " + CompanyInfo.BANK_BIK,
                CompanyInfo.BANK_ACCOUNT,
                CompanyInfo.PHONE + ", " + CompanyInfo.EMAIL);
    }

    private static int writeToInternal(Sheet sheet, Workbook wb,
            String fullName, String address, String innKpp, String ogrnOkved,
            String bank, String bankAccount, String contacts) {
        CellStyle companyStyle = company(wb);
        CellStyle detailStyle  = detail(wb);
        CellStyle separator    = separator(wb);

        int row = 0;

        Row r0 = sheet.createRow(row++);
        r0.setHeightInPoints(20f);
        Cell c0 = r0.createCell(0);
        c0.setCellValue(fullName);
        c0.setCellStyle(companyStyle);
        merge(sheet, r0.getRowNum(), 0, 4);

        row = writeKv(sheet, row, "ИНН / КПП", innKpp,    detailStyle);
        row = writeKv(sheet, row, "ОГРН / ОКВЭД", ogrnOkved, detailStyle);
        row = writeKv(sheet, row, "Адрес",      address,   detailStyle);
        row = writeKv(sheet, row, "Банк",        bank,      detailStyle);
        row = writeKv(sheet, row, "Расч. счёт",  bankAccount, detailStyle);
        row = writeKv(sheet, row, "Контакты",    contacts,  detailStyle);

        Row sep = sheet.createRow(row++);
        sep.setHeightInPoints(4f);
        Cell sc = sep.createCell(0);
        sc.setCellStyle(separator);
        merge(sheet, sep.getRowNum(), 0, 4);

        return row;
    }

    private static int writeKv(Sheet sheet, int rowIdx, String key, String value, CellStyle style) {
        Row r = sheet.createRow(rowIdx);
        Cell keyCell = r.createCell(0);
        keyCell.setCellValue(key + ":");
        keyCell.setCellStyle(style);

        Cell valueCell = r.createCell(1);
        valueCell.setCellValue(value);
        valueCell.setCellStyle(style);
        merge(sheet, rowIdx, 1, 4);
        return rowIdx + 1;
    }

    private static void merge(Sheet sheet, int row, int colStart, int colEnd) {
        if (colStart >= colEnd) return;
        sheet.addMergedRegion(new CellRangeAddress(row, row, colStart, colEnd));
    }

    private static CellStyle company(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 13);
        f.setColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private static CellStyle detail(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontHeightInPoints((short) 9);
        f.setColor(IndexedColors.GREY_80_PERCENT.getIndex());
        s.setFont(f);
        s.setWrapText(true);
        return s;
    }

    private static CellStyle separator(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }
}
