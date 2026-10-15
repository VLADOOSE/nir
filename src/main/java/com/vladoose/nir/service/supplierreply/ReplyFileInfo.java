package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;

import java.time.OffsetDateTime;

/** Файл ответа без байтов и таблицы — список источников экрана (байты тянуть ради списка незачем). */
public record ReplyFileInfo(Long id, String fileName, String mimeType, Long sizeBytes, ReplyFileKind kind,
                            ReplyParseStatus parseStatus, String parseNote, Integer pageCount, Long inboundEmailId,
                            String uploadedBy, OffsetDateTime createdAt) {}
