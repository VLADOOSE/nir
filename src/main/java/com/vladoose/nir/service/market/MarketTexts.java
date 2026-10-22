package com.vladoose.nir.service.market;

import com.vladoose.nir.service.tenderresults.ResultTexts;

/** Строки цены рынка (спека §10): у лота, подсказка, Telegram. */
public final class MarketTexts {

    private static final char NBSP = '\u00A0';

    private MarketTexts() {}

    /** «Рынок: медиана 1,34 млн ₸ за шт · 69 договоров · бюджет здесь 1,42 млн ₸ (+6 %)». */
    public static String lotLine(MarketStats s, String unit) {
        if (s.count() == 0) return null;
        StringBuilder b = new StringBuilder("Рынок: медиана ").append(ResultTexts.money(s.median())).append(" за ").append(unit)
                .append(" · ").append(contracts(s.count()));
        if (s.lotBudget() != null && s.budgetDiffPct() != null) {
            b.append(" · бюджет здесь ").append(ResultTexts.money(s.lotBudget())).append(" (").append(pct(s.budgetDiffPct())).append(')');
        }
        return b.toString();
    }

    /** «госзакуп: медиана 1,34 млн ₸ (69 дог.)». */
    public static String hintLine(MarketStats s) {
        return s.count() == 0 ? null : "госзакуп: медиана " + ResultTexts.money(s.median()) + " (" + s.count() + " дог.)";
    }

    /** «рынок: медиана 1,34 млн ₸ (69 дог.) · бюджет 1,42 млн ₸». */
    public static String telegramLine(MarketStats s) {
        if (s.count() == 0) return null;
        return "рынок: медиана " + ResultTexts.money(s.median()) + " (" + s.count() + " дог.)"
                + (s.lotBudget() == null ? "" : " · бюджет " + ResultTexts.money(s.lotBudget()));
    }

    /** «1 договор», «2 договора», «5 договоров». */
    public static String contracts(int n) {
        int d = n % 10, h = n % 100;
        String w = d == 1 && h != 11 ? "договор" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "договора" : "договоров";
        return n + " " + w;
    }

    static String pct(int p) {
        return (p < 0 ? "−" + (-p) : "+" + p) + NBSP + "%";
    }
}
