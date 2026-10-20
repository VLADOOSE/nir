package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.service.productwatch.fit.ProductCard;

import java.time.OffsetDateTime;

/** Всё для сверки модели в работе — одним чтением в транзакции; сеть (ndda.kz) — снаружи. */
public record ModelSnapshot(String text, ProductCard card, boolean needsDetail, String regNumber, LotModelFitResponse.Model model,
                            String note, String setBy, OffsetDateTime setAt) {}
