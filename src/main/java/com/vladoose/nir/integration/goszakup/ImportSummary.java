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
    private String message;
}
