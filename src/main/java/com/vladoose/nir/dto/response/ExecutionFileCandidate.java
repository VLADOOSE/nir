package com.vladoose.nir.dto.response;

import java.time.OffsetDateTime;

/** Файл переписки для окна «Файл этапа»: direction — IN (клиент) / OUT (мы). */
public record ExecutionFileCandidate(Long attachmentId, String fileName, Long sizeBytes, OffsetDateTime sentAt,
                                     String channel, String subject, String direction) {}
