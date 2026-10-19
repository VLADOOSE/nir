package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadDirection;

import java.time.OffsetDateTime;

/** Файл переписки сделки в любую сторону — без байтов (окно файла этапа, спека 2026-10-19-deal-execution §4.4). */
public record ChatEpisodeFile(Long attachmentId, String fileName, String mimeType, Long sizeBytes, OffsetDateTime sentAt,
                              String subject, LeadChannel channel, LeadDirection direction) {}
