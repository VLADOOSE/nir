package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.DealFileStage;

import java.time.OffsetDateTime;

/** Файл исполнения без байтов: вид раздела, лента, проверка повтора. */
public record DealFileMeta(Long id, DealFileStage stage, Long paymentId, Long dealSupplierId, String name, long size,
                           String sha256, String uploadedBy, OffsetDateTime uploadedAt) {}
