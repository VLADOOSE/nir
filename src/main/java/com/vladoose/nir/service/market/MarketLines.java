package com.vladoose.nir.service.market;

import java.util.List;
import java.util.Map;

/** Строка «рынок: …» к сообщениям Telegram (спека 2026-10-22-tender-results-market-prices §10). Ничего не бросает. */
public interface MarketLines {
    /** tenderId → строка по первому лоту из списка, у которого есть данные; не успели или нет данных — без строки. */
    Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender);
}
