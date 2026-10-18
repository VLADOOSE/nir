package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Разобранная таблица файла ответа (колонка `supplier_reply_file.parsed`, JSONB): шапка с ролями колонок и строки
 * данных с номерами строк файла. source — «Лист1» или «таблица 2»; headerRow — номер строки шапки в файле (с 1);
 * score — сколько заголовков узнано; currency — код валюты: по шапке / колонке «Валюта» / ценам, а нет там — по тексту
 * документа вне таблицы (вплотную к числу или явной фразой — MoneyText.textHits); MoneyText.MIXED — валюта не ясна (в
 * тексте разные валюты или суммы в тысячах): цены сами не подставляются, курс не применяется; null — не названа
 * (считается валютой запроса).
 * Незнакомые поля JSON пропускаются: строка, записанная более новой версией, не должна мешать открыть файл после
 * отката (тот же урок, что у OfferColumn, CLAUDE.md §8 «КП клиенту»).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplyTable(String source, int headerRow, int score, String currency, List<Column> columns, List<Row> rows) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Column(int index, String header, LineField role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(int fileRow, List<String> cells) {}

    /** Копия с новыми ролями колонок (индекс → роль); незатронутые колонки — как были. */
    public ReplyTable withRoles(Map<Integer, LineField> roles) {
        List<Column> out = new ArrayList<>(columns.size());
        for (Column c : columns) out.add(roles.containsKey(c.index()) ? new Column(c.index(), c.header(), roles.get(c.index())) : c);
        return new ReplyTable(source, headerRow, score, currency, out, rows);
    }
}
