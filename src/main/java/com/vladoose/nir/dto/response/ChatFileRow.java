package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LeadChannel;

import java.time.OffsetDateTime;

/** Файл нашего сообщения переписки — без байтов (решение при планировании 9): окно «Готовое КП» и подсказка. */
public record ChatFileRow(Long attachmentId, String fileName, String mimeType, Long sizeBytes, boolean hintDismissed,
                          OffsetDateTime sentAt, String subject, String body, LeadChannel channel) {}
