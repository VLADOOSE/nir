package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;

/** Файл ответа, разобранный вне транзакции (спека §3.1): bytes и sha256 — null у файла больше предела хранения. */
public record PreparedFile(String name, String mimeType, Long size, byte[] bytes, String sha256, ReplyFileKind kind,
                           ReplyParseStatus status, String note, ReplyTable table, Integer pageCount) {}
