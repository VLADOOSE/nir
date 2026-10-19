package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;

import java.math.BigDecimal;

/** Сведения о файле готового КП — без байтов и таблиц (ответ КП, журнал, лента): спека 2026-10-19 §6. */
public record ClientOfferFileMeta(Long offerId, String name, ReplyFileKind kind, long sizeBytes, ReplyParseStatus parseStatus,
                                  String parseNote, Integer pages, BigDecimal fileTotal, Long chatAttachmentId) {}
