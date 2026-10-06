package com.vladoose.nir.integration.goszakup;

import lombok.Data;

@Data
public class ImportSummary {
    private boolean enabled = true;
    private int fetched;
    private int matched;
    private int created;
    private int updated;
    private int skipped;
    private int errors;
    /** Прогресс для UI: страниц ленты прочитано / потолок страниц. */
    private int pagesRead;
    private int maxPages;
    /** Прогресс orgBin-импорта: больниц обработано / всего в реестре, текущая. */
    private int orgsTotal;
    private int orgsProcessed;
    private String currentOrgName;
    private String message;
    /** Последняя ошибка прогона «где: что» — в итоговый тост, чтобы причина была видна не только в логе. */
    private String lastError;

    /** Ошибка прогона: +1 к счётчику и её текст (без стека, не длиннее 300 символов). */
    public void addError(String text) {
        errors++;
        lastError = text == null || text.length() <= 300 ? text : text.substring(0, 299) + "…";
    }
}
