package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Номера объявлений (source_ext_id), СОЗДАННЫХ этим прогоном, — по ним уходит уведомление о новых тендерах
     * (NewTenderNotifier). В JSON статуса не отдаётся: UI он не нужен, а наполняется из потока импорта.
     */
    @JsonIgnore
    private final List<String> createdExtIds = new ArrayList<>();

    public void addCreated(String sourceExtId) {
        created++;
        createdExtIds.add(sourceExtId);
    }

    /**
     * Ошибка вне самого импорта (уведомление о новых тендерах): +1 к счётчику, а «последняя ошибка» ставится, только
     * если её ещё нет, — причина сбоя импорта важнее и не затирается.
     */
    public void addSideError(String text) {
        if (lastError == null) addError(text);
        else errors++;
    }

    /** Ошибка прогона: +1 к счётчику и её текст (без стека, не длиннее 300 символов). */
    public void addError(String text) {
        errors++;
        lastError = text == null || text.length() <= 300 ? text : text.substring(0, 299) + "…";
    }
}
