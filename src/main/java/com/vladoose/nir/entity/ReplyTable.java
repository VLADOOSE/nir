package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Разобранная таблица файла ответа (колонка `supplier_reply_file.parsed`, JSONB): шапка с ролями колонок и строки
 * данных с номерами строк файла. source — «Лист1» или «таблица 2»; headerRow — номер строки шапки в файле (с 1);
 * score — сколько заголовков узнано; currency — код валюты: по шапке / колонке «Валюта» / ценам, а нет там — по тексту
 * документа вне таблицы (вплотную к числу или явной фразой — MoneyText.textHits), или выбранная человеком на экране
 * сверки; MoneyText.MIXED — валюта не ясна (в тексте разные валюты или суммы в тысячах): цены сами не подставляются,
 * курс не применяется; null — не названа (считается валютой запроса). currencyFrom — откуда валюта (у таблиц,
 * разобранных до 2026-10-18, его нет — см. {@link #currencySource()}).
 * Незнакомые поля JSON пропускаются: строка, записанная более новой версией, не должна мешать открыть файл после
 * отката (тот же урок, что у OfferColumn, CLAUDE.md §8 «КП клиенту»).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplyTable(String source, int headerRow, int score, String currency, List<Column> columns, List<Row> rows,
                         CurrencyFrom currencyFrom) {

    /** Без источника валюты (тесты; так выглядят таблицы до 2026-10-18): источник выводится из currency. */
    public ReplyTable(String source, int headerRow, int score, String currency, List<Column> columns, List<Row> rows) {
        this(source, headerRow, score, currency, columns, rows, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Column(int index, String header, LineField role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(int fileRow, List<String> cells) {}

    /**
     * Откуда валюта: kind; texts — фрагменты текста документа, где она нашлась (TEXT — один, MIXED — по одному на
     * валюту, до трёх); by и at — кто и когда выбрал её на экране сверки (MANUAL; at — ISO-8601 со смещением).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrencyFrom(ReplyCurrencySource kind, List<String> texts, String by, String at) {}

    /**
     * Откуда валюта. У таблиц, разобранных до 2026-10-18, источника нет — по самой валюте: не названа — NONE,
     * «MIXED» (MoneyText.MIXED) — MIXED, иначе TABLE (тогда валюту знала только таблица).
     */
    @JsonIgnore
    public ReplyCurrencySource currencySource() {
        if (currencyFrom != null && currencyFrom.kind() != null) return currencyFrom.kind();
        if (currency == null) return ReplyCurrencySource.NONE;
        return "MIXED".equals(currency) ? ReplyCurrencySource.MIXED : ReplyCurrencySource.TABLE;
    }

    /** Копия с новыми ролями колонок (индекс → роль); незатронутые колонки — как были. */
    public ReplyTable withRoles(Map<Integer, LineField> roles) {
        List<Column> out = new ArrayList<>(columns.size());
        for (Column c : columns) out.add(roles.containsKey(c.index()) ? new Column(c.index(), c.header(), roles.get(c.index())) : c);
        return new ReplyTable(source, headerRow, score, currency, out, rows, currencyFrom);
    }

    /** Копия с другой валютой и её источником (валюта, выбранная на экране сверки). */
    public ReplyTable withCurrency(String code, CurrencyFrom from) {
        return new ReplyTable(source, headerRow, score, code, columns, rows, from);
    }
}
