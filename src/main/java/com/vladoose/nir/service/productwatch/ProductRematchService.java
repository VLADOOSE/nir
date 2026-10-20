package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Пересчёт отметок по тендерам KZ с идущим приёмом (спека §8): после сохранения групп и при старте с включённым блоком.
 * Молча: тендеру, у которого отметок до пересчёта не было, уровень сразу считается сообщённым; тендер с отметкой
 * поиска или импорта, чьё сообщение ещё не ушло, пересчёт не «гасит».
 */
@Service
public class ProductRematchService {

    private final TenderRepository tenders;
    private final ProductMatchService matches;
    private final Runnable refreshCatalog;

    @Autowired
    public ProductRematchService(TenderRepository tenders, ProductMatchService matches, ProductCatalog catalog) {
        this(tenders, matches, catalog::refresh);
    }

    public ProductRematchService(TenderRepository tenders, ProductMatchService matches, Runnable refreshCatalog) {
        this.tenders = tenders;
        this.matches = matches;
        this.refreshCatalog = refreshCatalog;
    }

    public int rematchOpen() {
        refreshCatalog.run();
        int marked = 0;
        for (Long id : tenders.findOpenIds(Market.KZ)) {
            ProductMatchService.Result r = matches.evaluate(id, ProductMatchSource.REMATCH);
            if (r.level() == null) continue;
            marked++;
            if (!r.hadMarks()) matches.markNotified(id, r.level());
        }
        return marked;
    }
}
