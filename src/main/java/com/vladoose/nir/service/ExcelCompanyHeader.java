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
 * Шапка компании активного рынка для Excel-листа: наименование и строки реквизитов (CompanyInfoProvider), ячейки A:E
 * объединены, под шапкой — серая линия-разделитель.
 *
 * Возвращает индекс первой "свободной" строки — продолжать запись данных нужно с неё.
 */
public final class ExcelCompanyHeader {

    private ExcelCompanyHeader() {}

    /** Шапка с реквизитами активного рынка; возвращает индекс первой свободной строки. */
    public static int writeTo(Sheet sheet, Workbook wb, CompanyInfoProvider.Company company) {
        CellStyle companyStyle = company(wb);
        CellStyle detailStyle = detail(wb);
        CellStyle separator = separator(wb);

        int row = 0;
        Row r0 = sheet.createRow(row++);
        r0.setHeightInPoints(20f);
        Cell c0 = r0.createCell(0);
        c0.setCellValue(company.fullName());
        c0.setCellStyle(companyStyle);
        merge(sheet, r0.getRowNum(), 0, 4);

        for (String line : company.lines()) {
            Row r = sheet.createRow(row++);
            Cell c = r.createCell(0);
            c.setCellValue(line);
            c.setCellStyle(detailStyle);
            merge(sheet, r.getRowNum(), 0, 4);
        }

        Row sep = sheet.createRow(row++);
        sep.setHeightInPoints(4f);
        Cell sc = sep.createCell(0);
        sc.setCellStyle(separator);
        merge(sheet, sep.getRowNum(), 0, 4);
        return row;
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
