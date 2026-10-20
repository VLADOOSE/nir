package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.ProductGroupRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Снимок групп и каталога в памяти (рынок KZ — блок только для Казахстана). Обновляется после загрузки каталога и
 * сохранения групп; первый вызов строит лениво. Чтение репозиториев — своей короткой транзакцией (фоновые потоки).
 */
@Component
public class ProductCatalog implements CatalogSource {

    private final ProductGroupRepository groups;
    private final SiteProductRepository products;
    private final TransactionTemplate readTx;
    private volatile CatalogSnapshot snapshot;

    public ProductCatalog(ProductGroupRepository groups, SiteProductRepository products, PlatformTransactionManager tm) {
        this.groups = groups;
        this.products = products;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    public CatalogSnapshot get() {
        CatalogSnapshot s = snapshot;
        if (s == null) {
            refresh();
            s = snapshot;
        }
        return s;
    }

    public synchronized void refresh() {
        snapshot = readTx.execute(st -> CatalogSnapshot.build(
                groups.findByMarketAndActiveTrueOrderByPositionAscIdAsc(Market.KZ),
                products.findByMarketAndRemovedAtIsNull(Market.KZ)));
    }

    /** Сбросить снимок — следующий {@link #get()} построит заново (тесты: снимок не должен пережить откат транзакции). */
    public void invalidate() {
        snapshot = null;
    }
}
