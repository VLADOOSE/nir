package com.vladoose.nir.imports;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.service.RuleBasedLineExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedLineExtractorTest {

    private final RuleBasedLineExtractor extractor = new RuleBasedLineExtractor();

    private byte[] xlsx(String[] header, Object[]... rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) h.createCell(c).setCellValue(header[c]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number) row.createCell(c).setCellValue(((Number) v).doubleValue());
                    else row.createCell(c).setCellValue(String.valueOf(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void mapsColumnsByLearnedSynonyms_andParsesRows() throws Exception {
        byte[] file = xlsx(
            new String[]{"Наименование", "Производитель", "Кол-во"},
            new Object[]{"ЭКГ BeneHeart R12", "Mindray", 2},
            new Object[]{"Криосауна", "CryoSpace", 1}
        );
        Map<String, LineField> learned = Map.of(
            "наименование", LineField.NAME,
            "производитель", LineField.MANUFACT,
            "кол-во", LineField.QUANTITY
        );

        ImportPreviewResponse p = extractor.extract(file, "z.xlsx", learned);

        assertThat(p.getColumns()).extracting("field")
            .containsExactly(LineField.NAME, LineField.MANUFACT, LineField.QUANTITY);
        assertThat(p.getRows()).hasSize(2);
        assertThat(p.getRows().get(0)).containsExactly("ЭКГ BeneHeart R12", "Mindray", "2");
    }

    @Test
    void unknownHeader_isIgnore() throws Exception {
        byte[] file = xlsx(new String[]{"Загадочная колонка"}, new Object[]{"x"});
        ImportPreviewResponse p = extractor.extract(file, "z.xlsx", Map.of());
        assertThat(p.getColumns().get(0).getField()).isEqualTo(LineField.IGNORE);
    }
}
