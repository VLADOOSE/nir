package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Отметки «наш товар» у тендеров, созданных и обновлённых импортом ЗКО и СК-Фармации (спека §8): в конце прогона,
 * в его потоке и под рынком KZ, без запросов к площадке. ТЗ таких лотов сверит ближайший поиск по стране.
 */
@Component
public class ProductImportMarks {

    private static final Logger log = LoggerFactory.getLogger(ProductImportMarks.class);

    private final ProductMatchService matches;
    private final TenderRepository tenders;
    private final boolean enabled;

    public ProductImportMarks(ProductMatchService matches, TenderRepository tenders,
                              @Value("${product-watch.enabled:false}") boolean enabled) {
        this.matches = matches;
        this.tenders = tenders;
        this.enabled = enabled;
    }

    public void afterImport(ImportSummary sum) {
        if (!enabled) return;
        for (String ext : sum.getTouchedExtIds()) {
            try {
                tenders.findBySourceExtId(ext).ifPresent(t -> matches.evaluate(t.getId(), ProductMatchSource.IMPORT));
            } catch (RuntimeException e) {
                sum.addSideError("отметки «наш товар» " + ext + ": " + ErrorText.of(e));
                log.warn("Отметки «наш товар» у {}: {}", ext, ErrorText.of(e));
            }
        }
    }
}
