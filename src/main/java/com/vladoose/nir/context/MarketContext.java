package com.vladoose.nir.context;

import com.vladoose.nir.entity.Market;

/** Активный рынок текущего HTTP-запроса (ThreadLocal). */
public final class MarketContext {
    private static final ThreadLocal<Market> CURRENT = new ThreadLocal<>();

    private MarketContext() {}

    public static Market get() {
        Market m = CURRENT.get();
        return m != null ? m : Market.RF;
    }
    public static void set(Market market) { CURRENT.set(market); }
    public static void clear() { CURRENT.remove(); }
}
