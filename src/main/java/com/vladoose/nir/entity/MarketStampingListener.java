package com.vladoose.nir.entity;

import com.vladoose.nir.context.MarketContext;
import jakarta.persistence.PrePersist;

/** Штампует активный рынок на любую из 6 «вселенных» сущностей при INSERT (create).
 *  @PrePersist срабатывает только на persist (не на merge/update), поэтому update сохраняет рынок. */
public class MarketStampingListener {
    @PrePersist
    public void stampMarket(Object entity) {
        if (entity instanceof MarketScoped ms && ms.getMarket() == null) {
            ms.setMarket(MarketContext.get());
        }
    }
}
