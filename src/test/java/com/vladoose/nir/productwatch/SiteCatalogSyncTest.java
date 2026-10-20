package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.WestmedApiException;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.SiteCatalogSync;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Загрузка каталога сайта (спека §6): новые — вставка, изменённые — обновление, пропавшие — removed_at. */
@SpringBootTest
@Transactional
class SiteCatalogSyncTest {

    @Autowired SiteProductRepository products;
    @Autowired EntityManager em;
    @Autowired com.vladoose.nir.service.productwatch.ProductCatalog catalog;

    FakeWestmedCatalogClient fake;
    SiteCatalogSync sync;

    static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        products.deleteAllInBatch();          // своя картина в откатываемой транзакции (CLAUDE.md §14)
        em.flush();
        fake = new FakeWestmedCatalogClient();
        sync = new SiteCatalogSync(fake, products, catalog);
    }

    @AfterEach void clear() { MarketContext.clear(); catalog.invalidate(); }

    private static WestmedCatalogItem item(UUID id, String name, String description) {
        return new WestmedCatalogItem(id, name, "sku", "slug-" + id.toString().substring(35), "Фотек", "Хирургия",
                "кратко", description, List.of(new SiteProductAttribute("Мощность", "350 Вт")), "РК-МТ", Instant.parse("2026-10-10T05:00:00Z"));
    }

    @Test
    void addThenUpdateThenRemove() {
        fake.items = List.of(item(A, "Блок аспирации", "состав"), item(B, "ЭХВЧ-350-03", null));
        SiteCatalogSync.SyncResult r1 = sync.sync();
        assertThat(r1.added()).isEqualTo(2);
        assertThat(r1.full()).isTrue();
        assertThat(products.findByMarketAndRemovedAtIsNull(Market.KZ)).hasSize(2);

        fake.items = List.of(item(A, "Блок аспирации для АСД", "новый состав"));
        SiteCatalogSync.SyncResult r2 = sync.sync();
        assertThat(r2.updated()).isEqualTo(1);
        assertThat(r2.removed()).isEqualTo(1);
        SiteProduct a = products.findBySiteId(A).orElseThrow();
        assertThat(a.getName()).isEqualTo("Блок аспирации для АСД");
        assertThat(a.getDescription()).isEqualTo("новый состав");
        assertThat(products.findBySiteId(B).orElseThrow().getRemovedAt()).isNotNull();

        fake.items = List.of(item(A, "Блок аспирации для АСД", "новый состав"), item(B, "ЭХВЧ-350-03", null));
        sync.sync();
        assertThat(products.findBySiteId(B).orElseThrow().getRemovedAt()).as("вернулся на сайт").isNull();
    }

    @Test
    void withoutAdmin_publicList() {
        fake.admin = false;
        fake.items = List.of(item(A, "Блок аспирации", null));
        SiteCatalogSync.SyncResult r = sync.sync();
        assertThat(r.full()).isFalse();
        assertThat(fake.publicCalls).isEqualTo(1);
        assertThat(fake.adminCalls).isZero();
    }

    @Test
    void siteDown_502_catalogKept() {
        fake.items = List.of(item(A, "Блок аспирации", null));
        sync.sync();
        fake.fail = new WestmedApiException(0, "сайт недоступен: ConnectException");
        assertThatThrownBy(() -> sync.sync()).isInstanceOf(UpstreamException.class).hasMessageContaining("Каталог сайта не загрузился");
        assertThat(products.findByMarketAndRemovedAtIsNull(Market.KZ)).hasSize(1);
    }

    @Test
    void emptyAnswer_doesNotRemoveEverything() {
        fake.items = List.of(item(A, "Блок аспирации", null));
        sync.sync();
        fake.items = List.of();
        assertThatThrownBy(() -> sync.sync()).isInstanceOf(UpstreamException.class).hasMessageContaining("пустой");
        assertThat(products.findBySiteId(A).orElseThrow().getRemovedAt()).isNull();
    }
}
