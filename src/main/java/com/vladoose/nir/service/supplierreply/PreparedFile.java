package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.entity.TextPrice;

import java.util.List;

/**
 * Файл ответа, разобранный вне транзакции (спека §3.1): bytes и sha256 — null у файла больше предела хранения; textPrices —
 * цены в тексте файла без таблицы (Word, PDF; пусто — нет).
 */
public record PreparedFile(String name, String mimeType, Long size, byte[] bytes, String sha256, ReplyFileKind kind,
                           ReplyParseStatus status, String note, ReplyTable table, Integer pageCount, List<TextPrice> textPrices) {

    /** Без цен в тексте (таблица, картинка, тесты). */
    public PreparedFile(String name, String mimeType, Long size, byte[] bytes, String sha256, ReplyFileKind kind,
                        ReplyParseStatus status, String note, ReplyTable table, Integer pageCount) {
        this(name, mimeType, size, bytes, sha256, kind, status, note, table, pageCount, List.of());
    }
}
