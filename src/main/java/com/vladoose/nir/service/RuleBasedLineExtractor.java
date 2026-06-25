package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.dto.response.PreviewColumnResponse;
import com.vladoose.nir.entity.LineField;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RuleBasedLineExtractor implements LineExtractor {

    @Override
    public ImportPreviewResponse extract(byte[] content, String filename, Map<String, LineField> learned) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            int headerRowIdx = firstNonEmptyRow(sheet);
            if (headerRowIdx < 0) {
                throw new IllegalArgumentException("Файл пустой — нет строки заголовков");
            }
            Row headerRow = sheet.getRow(headerRowIdx);
            int colCount = headerRow.getLastCellNum();

            List<PreviewColumnResponse> columns = new ArrayList<>();
            for (int c = 0; c < colCount; c++) {
                String header = cellString(headerRow.getCell(c));
                PreviewColumnResponse col = new PreviewColumnResponse();
                col.setIndex(c);
                col.setHeader(header);
                col.setField(learned.getOrDefault(header.trim().toLowerCase(), LineField.IGNORE));
                columns.add(col);
            }

            List<List<String>> rows = new ArrayList<>();
            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                boolean anyValue = false;
                for (int c = 0; c < colCount; c++) {
                    String v = cellString(row.getCell(c));
                    if (!v.isBlank()) anyValue = true;
                    cells.add(v);
                }
                if (anyValue) rows.add(cells);
            }

            ImportPreviewResponse preview = new ImportPreviewResponse();
            preview.setColumns(columns);
            preview.setRows(rows);
            return preview;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Не удалось прочитать файл (ожидается Excel .xlsx/.xls): " + e.getMessage());
        }
    }

    private int firstNonEmptyRow(Sheet sheet) {
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            short last = row.getLastCellNum();
            for (int c = 0; c < last; c++) {
                if (!cellString(row.getCell(c)).isBlank()) return r;
            }
        }
        return -1;
    }

    private String cellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }
}
