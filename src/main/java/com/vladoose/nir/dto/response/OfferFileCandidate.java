package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Файл переписки для окна «Готовое КП» (спека 2026-10-19 §3.2): sentOn — дата отправки по часам рынка (решение при
 * планировании 12); usedBy… — уже занесён в это КП; aisOffer… — это PDF / Word самой АИС с КП этой сделки.
 */
public record OfferFileCandidate(Long attachmentId, String fileName, Long sizeBytes, String kind, OffsetDateTime sentAt,
                                 LocalDate sentOn, String channel, String subject, String text, boolean looksLikeOffer,
                                 Long usedByOfferId, String usedByOfferLabel, Long aisOfferId, String aisOfferLabel,
                                 boolean aisOfferDraft) {}
