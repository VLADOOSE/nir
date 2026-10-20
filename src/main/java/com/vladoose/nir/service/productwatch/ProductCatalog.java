package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductRegistration;
import com.vladoose.nir.entity.RegistrationComponent;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.ProductGroupRepository;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.fit.ForeignBrand;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Снимок групп и каталога в памяти (рынок KZ — блок только для Казахстана). Обновляется после загрузки каталога и
 * сохранения групп; первый вызов строит лениво. Чтение репозиториев — своей короткой транзакцией (фоновые потоки).
 */
@Component
public class ProductCatalog implements CatalogSource {

    private final ProductGroupRepository groups;
    private final SiteProductRepository products;
    private final ProductRegistrationRepository registrations;
    private final MedRegistryRepository registry;
    private final TransactionTemplate readTx;
    private volatile CatalogSnapshot snapshot;

    public ProductCatalog(ProductGroupRepository groups, SiteProductRepository products, ProductRegistrationRepository registrations,
                          MedRegistryRepository registry, PlatformTransactionManager tm) {
        this.groups = groups;
        this.products = products;
        this.registrations = registrations;
        this.registry = registry;
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

    /** Группы, товары сайта, их РУ (проверка на ndda.kz, V39) и бренды производителей реестра — одной транзакцией. */
    public synchronized void refresh() {
        snapshot = readTx.execute(st -> {
            Map<String, RegistrationInfo> regs = new HashMap<>();
            for (ProductRegistration r : registrations.findAll()) {
                regs.put(r.getRegNumber(), new RegistrationInfo(r.getRegNumber(), r.getStatus(), r.getExpirationDate(),
                        Boolean.TRUE.equals(r.getUnlimited()), r.getPurpose(), r.getUseArea(), r.getTechChars(),
                        r.getComplect() == null ? List.of()
                                : r.getComplect().stream().map(RegistrationComponent::component).filter(Objects::nonNull).toList()));
            }
            return CatalogSnapshot.build(groups.findByMarketAndActiveTrueOrderByPositionAscIdAsc(Market.KZ),
                    products.findByMarketAndRemovedAtIsNull(Market.KZ), regs, ForeignBrand.registryBrands(registry.findDistinctProducers()));
        });
    }

    /** Сбросить снимок — следующий {@link #get()} построит заново (тесты: снимок не должен пережить откат транзакции). */
    public void invalidate() {
        snapshot = null;
    }
}
