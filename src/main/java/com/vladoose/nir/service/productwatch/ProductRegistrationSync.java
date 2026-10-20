package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.NddaRegistration;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * РУ товаров сайта на ndda.kz (спека 2026-10-20-product-spec-fit §4.2): по каждому разному №, пауза 300 мс; сеть — вне
 * транзакции, запись — {@link ProductRegistrationWriter}; в конце — снимок правил заново ({@link ProductCatalog#refresh()}).
 */
@Service
public class ProductRegistrationSync {

    private static final Logger log = LoggerFactory.getLogger(ProductRegistrationSync.class);
    public static final long PAUSE_MS = 300;

    public record Result(int numbers, int active, int expired, int notFound, int errors) {
        public String text() {
            return "РУ: проверено " + numbers + " (действует " + active + ", истекло " + expired + ", не найдено " + notFound
                    + (errors > 0 ? ", ошибок " + errors : "") + ")";
        }
    }

    private final SiteProductRepository products;
    private final NddaClient ndda;
    private final ProductRegistrationWriter writer;
    private final TransactionTemplate readTx;
    private final Runnable refreshCatalog;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public ProductRegistrationSync(SiteProductRepository products, NddaClient ndda, ProductRegistrationWriter writer,
                                   PlatformTransactionManager tm, ProductCatalog catalog) {
        this(products, ndda, writer, tm, catalog::refresh, UpstreamRetry.REAL);
    }

    public ProductRegistrationSync(SiteProductRepository products, NddaClient ndda, ProductRegistrationWriter writer,
                                   PlatformTransactionManager tm, Runnable refreshCatalog, UpstreamRetry.Sleeper sleeper) {
        this.products = products; this.ndda = ndda; this.writer = writer; this.refreshCatalog = refreshCatalog; this.sleeper = sleeper;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    public Result sync() {
        List<String> numbers = readTx.execute(st -> products.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .map(SiteProduct::getRegistrationCert).map(RegNumbers::normalize).filter(Objects::nonNull).distinct().sorted().toList());
        int active = 0, expired = 0, notFound = 0, errors = 0;
        for (String n : numbers == null ? List.<String>of() : numbers) {
            pause();
            try {
                NddaRegistration r = ndda.findRegistration(n);
                if (r == null) {
                    writer.notFound(n);
                    notFound++;
                    continue;
                }
                writer.save(n, r, ndda.fetchDetail(r.id()), ndda.fetchComplectList(r.id()));
                if (r.active()) active++; else expired++;
            } catch (RuntimeException e) {
                writer.error(n, ErrorText.of(e));
                errors++;
            }
        }
        Result res = new Result(numbers == null ? 0 : numbers.size(), active, expired, notFound, errors);
        if (errors > 0) log.warn("Наша продукция: {}", res.text()); else log.info("Наша продукция: {}", res.text());
        refreshCatalog.run();
        return res;
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
