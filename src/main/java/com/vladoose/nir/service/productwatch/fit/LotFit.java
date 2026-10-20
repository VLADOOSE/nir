package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitVerdict;

import java.util.List;

/** Итог сверки лота: вердикт лучшего товара, лучший + до 2 вариантов, чужой бренд ТЗ. */
public record LotFit(FitVerdict verdict, List<FitCandidate> candidates, String foreignBrand) {
    public FitCandidate best() { return candidates.isEmpty() ? null : candidates.get(0); }
}
