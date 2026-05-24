package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ProfitabilityReportResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class ProfitabilityExcelService {

    private final ProfitabilityReportService reportService;

    public ProfitabilityExcelService(ProfitabilityReportService reportService) {
        this.reportService = reportService;
    }

    public byte[] generate() throws IOException {
        ProfitabilityReportResponse report = reportService.buildReport();

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle title = titleStyle(wb);
            CellStyle header = headerStyle(wb);
            CellStyle label = labelStyle(wb);
            CellStyle money = moneyStyle(wb);
            CellStyle percent = percentStyle(wb);

            // --- Лист 1: Сводка ---
            Sheet summarySheet = wb.createSheet("Сводка");
            Row r0 = summarySheet.createRow(0);
            Cell c0 = r0.createCell(0);
            c0.setCellValue("Отчёт по прибыльности — ООО «Регион-Мед»");
            c0.setCellStyle(title);
            summarySheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

            Row r1 = summarySheet.createRow(1);
            r1.createCell(0).setCellValue("Дата отчёта:");
            r1.createCell(1).setCellValue(LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));

            ProfitabilityReportResponse.Summary s = report.getSummary();
            int row = 3;
            putKV(summarySheet, row++, "Выиграно заявок", val(s.getWonApplies()), label, null);
            putKV(summarySheet, row++, "Выручка", num(s.getTotalRevenue()), label, money);
            putKV(summarySheet, row++, "Закупка", num(s.getTotalProcurement()), label, money);
            putKV(summarySheet, row++, "Прибыль", num(s.getTotalProfit()), label, money);
            putKV(summarySheet, row++, "Маржинальность, %", num(s.getMarginPercent()), label, percent);
            putKV(summarySheet, row++, "Средняя прибыль на заявку", num(s.getAvgChequeProfit()), label, money);

            summarySheet.setColumnWidth(0, 8000);
            summarySheet.setColumnWidth(1, 5000);

            // --- Лист 2: Топ тендеров ---
            Sheet topSheet = wb.createSheet("Топ тендеров");
            writeRow(topSheet, 0, new String[]{"№ тендера", "Заказчик", "Выручка", "Прибыль", "Маржа %"}, header);
            int rt = 1;
            for (var t : report.getTopTenders()) {
                Row r = topSheet.createRow(rt++);
                r.createCell(0).setCellValue(t.getTenderNumber() != null ? t.getTenderNumber() : "");
                r.createCell(1).setCellValue(t.getFacilityName() != null ? t.getFacilityName() : "");
                setMoneyCell(r.createCell(2), t.getRevenue(), money);
                setMoneyCell(r.createCell(3), t.getProfit(), money);
                setPercentCell(r.createCell(4), t.getMarginPercent(), percent);
            }
            autosize(topSheet, 5);

            // --- Лист 3: Дистрибьюторы ---
            Sheet distSheet = wb.createSheet("Дистрибьюторы");
            writeRow(distSheet, 0, new String[]{"Дистрибьютор", "Сделок", "Прибыль", "Средняя маржа %"}, header);
            int rd = 1;
            for (var d : report.getDistributorRanking()) {
                Row r = distSheet.createRow(rd++);
                r.createCell(0).setCellValue(d.getName() != null ? d.getName() : "");
                r.createCell(1).setCellValue(d.getDealsCount());
                setMoneyCell(r.createCell(2), d.getTotalProfit(), money);
                setPercentCell(r.createCell(3), d.getAvgMarginPercent(), percent);
            }
            autosize(distSheet, 4);

            // --- Лист 4: Типы оборудования ---
            Sheet typeSheet = wb.createSheet("Типы оборудования");
            writeRow(typeSheet, 0, new String[]{"Тип", "Позиций", "Прибыль", "Средняя маржа %"}, header);
            int rty = 1;
            for (var t : report.getProfitByType()) {
                Row r = typeSheet.createRow(rty++);
                r.createCell(0).setCellValue(t.getTypeName() != null ? t.getTypeName() : "");
                r.createCell(1).setCellValue(t.getPositionsCount());
                setMoneyCell(r.createCell(2), t.getTotalProfit(), money);
                setPercentCell(r.createCell(3), t.getAvgMarginPercent(), percent);
            }
            autosize(typeSheet, 4);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private void putKV(Sheet sheet, int row, String labelText, Object value, CellStyle labelStyle, CellStyle valueStyle) {
        Row r = sheet.createRow(row);
        Cell labelCell = r.createCell(0);
        labelCell.setCellValue(labelText);
        labelCell.setCellStyle(labelStyle);
        Cell valueCell = r.createCell(1);
        if (value instanceof Number n) {
            valueCell.setCellValue(n.doubleValue());
            if (valueStyle != null) valueCell.setCellStyle(valueStyle);
        } else if (value != null) {
            valueCell.setCellValue(value.toString());
        }
    }

    private void writeRow(Sheet sheet, int rowIdx, String[] headers, CellStyle style) {
        Row r = sheet.createRow(rowIdx);
        for (int i = 0; i < headers.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(style);
        }
    }

    private void setMoneyCell(Cell c, BigDecimal v, CellStyle s) {
        if (v != null) { c.setCellValue(v.doubleValue()); c.setCellStyle(s); }
    }

    private void setPercentCell(Cell c, BigDecimal v, CellStyle s) {
        if (v != null) { c.setCellValue(v.doubleValue() / 100.0); c.setCellStyle(s); }
    }

    private void autosize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) sheet.autoSizeColumn(i);
    }

    private Number num(BigDecimal v) { return v != null ? v.doubleValue() : 0.0; }
    private int val(Integer v) { return v != null ? v : 0; }

    private CellStyle titleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont(); f.setBold(true); f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        return s;
    }
    private CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont(); f.setBold(true); f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }
    private CellStyle labelStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont(); f.setBold(true);
        s.setFont(f);
        return s;
    }
    private CellStyle moneyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0.00 \"₽\""));
        return s;
    }
    private CellStyle percentStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("0.00%"));
        return s;
    }
}
