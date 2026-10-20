package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.service.RegistryDetailService;
import com.vladoose.nir.service.productwatch.fit.SpecFit;
import com.vladoose.nir.service.productwatch.fit.SpecRequirements;
import org.springframework.stereotype.Service;

/**
 * Сверка модели в работе с ТЗ лота (спека 2026-10-20-lot-model-in-work §6): ничего не хранится — по свежему тексту ТЗ и
 * карточке. Карточку РУ модели реестра, которую ещё не открывали, — один раз с ndda.kz, вне транзакции.
 */
@Service
public class LotModelFit {

    static final String MISSING_TEXT = "Карточка РУ недоступна — повторите позже";

    private final LotModelReader reader;
    private final RegistryDetailService details;
    private final LotModelViews views;

    public LotModelFit(LotModelReader reader, RegistryDetailService details, LotModelViews views) {
        this.reader = reader;
        this.details = details;
        this.views = views;
    }

    public LotModelFitResponse fit(Long lotId) {
        ModelSnapshot s = reader.read(lotId);
        if (s.model() == null) return new LotModelFitResponse(null, null, null, null, "NONE", null, null);
        if (s.needsDetail()) {
            if (!prefetchCard(s.regNumber())) {
                return new LotModelFitResponse(s.model(), s.note(), s.setBy(), s.setAt(), "MISSING", MISSING_TEXT, null);
            }
            s = reader.read(lotId);
        }
        if (s.card() == null) return new LotModelFitResponse(s.model(), s.note(), s.setBy(), s.setAt(), "NONE", null, null);
        FitCandidate c = SpecFit.compare(SpecRequirements.extract(s.text()), s.card());
        return new LotModelFitResponse(s.model(), s.note(), s.setBy(), s.setAt(), "OK", null, views.candidate(c));
    }

    /** Карточка РУ с ndda.kz (кеш — в реестре); сбой — false: закрепление и сверка не падают. */
    public boolean prefetchCard(String regNumber) {
        if (regNumber == null) return false;
        try {
            details.detail(regNumber);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
