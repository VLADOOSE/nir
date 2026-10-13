package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RegistrySuggestionsRequest;
import com.vladoose.nir.dto.response.RegistrySuggestionResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.privaterequest.PrivateRequestRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Подсказки РУ для строк КП (спека 2026-10-13 §2.3): ничего не пишет — правки строк уходят автосохранением КП. */
@Service
public class ClientOfferRegistryService {

    public static final int MAX_ROWS = 500;

    private final PrivateRequestRegistry registry;

    public ClientOfferRegistryService(PrivateRequestRegistry registry) {
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<RegistrySuggestionResponse> suggestions(List<RegistrySuggestionsRequest.Row> rows) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Реестр РУ — только для Казахстана");
        if (rows == null || rows.isEmpty()) return List.of();
        if (rows.size() > MAX_ROWS) throw new BadRequestException("За раз — не больше " + MAX_ROWS + " позиций");
        List<RegistrySuggestionResponse> out = new ArrayList<>();
        for (RegistrySuggestionsRequest.Row r : rows) {
            if (r == null || r.name() == null || r.name().isBlank()) continue;
            String text = r.model() == null || r.model().isBlank() ? r.name().trim() : r.name().trim() + " " + r.model().trim();
            out.add(registry.suggestion(text, r.manufacturer())
                    .map(c -> RegistrySuggestionResponse.suggested(r.key(), c))
                    .orElseGet(() -> RegistrySuggestionResponse.notFound(r.key())));
        }
        return out;
    }
}
