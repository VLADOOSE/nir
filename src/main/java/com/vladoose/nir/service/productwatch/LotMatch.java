package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;

import java.util.List;

/** Отметка лота по группе (результат правил, до записи). */
public record LotMatch(long lotId, long groupId, String word, ProductMatchLevel level, String evidence, Long productId,
                       List<SimilarProductRef> similar, boolean specUsed) {}
