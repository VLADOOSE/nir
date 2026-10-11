package com.vladoose.nir.integration;

import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.ApplyItemRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Лоты, снятые с площадки (B5). Лот, которого больше нет в списке площадки, удаляет orphanRemoval — но если на него
 * уже есть запрос КП или позиция заявки, удаление упирается во внешний ключ ({@code price_request_item} /
 * {@code apply_item} без ON DELETE): транзакция тендера откатывалась целиком на каждом прогоне, и тендер «замерзал» —
 * статус, отмена и новые лоты не доезжали. Такие лоты остаются с отметкой «снят с площадки»
 * ({@code source_removed_at}); лоты без работы удаляются, как раньше.
 */
@Component
public class SourceRemovedLots {

    private final PriceRequestItemRepository priceRequestItems;
    private final ApplyItemRepository applyItems;

    public SourceRemovedLots(PriceRequestItemRepository priceRequestItems, ApplyItemRepository applyItems) {
        this.priceRequestItems = priceRequestItems;
        this.applyItems = applyItems;
    }

    /**
     * Лоты {@code existing}, которых нет в {@code result}: с запросом КП или позицией заявки — помечаются и
     * дописываются в конец {@code result} (отметка ставится один раз, при первом снятии); остальные не дописываются
     * и уйдут через orphanRemoval. Сравнение по ссылке — {@code result} держит те же управляемые объекты.
     *
     * @return дописанные лоты (в порядке {@code existing})
     */
    public List<TenderLot> keepReferenced(List<TenderLot> existing, List<TenderLot> result, OffsetDateTime now) {
        Set<TenderLot> inResult = Collections.newSetFromMap(new IdentityHashMap<>());
        inResult.addAll(result);
        List<TenderLot> kept = new ArrayList<>();
        for (TenderLot old : List.copyOf(existing)) {
            if (inResult.contains(old) || old.getId() == null) continue;
            if (priceRequestItems.existsByTenderLotId(old.getId()) || applyItems.existsByTenderLotId(old.getId())) {
                if (old.getSourceRemovedAt() == null) old.setSourceRemovedAt(now);
                kept.add(old);
            }
        }
        result.addAll(kept);
        return kept;
    }
}
