package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotModelSearchResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.lotmodel.LotModelSearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Поиск «модели в работе» (спека §5.4): слова — в названии, бренде или артикуле; снятые не ищутся; точный № РУ — первым. */
@SpringBootTest
@Transactional
class LotModelSearchTest {

    @Autowired LotModelSearch search;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZQ Лот");
        products.saveAndFlush(product("ZZQ Дефибриллятор ДКИ-Н-12", "Аксион", "ZZQ-777", null));
        products.saveAndFlush(product("ZZQ Дефибриллятор старый", "Аксион", "ZZQ-778", OffsetDateTime.now()));
        registry.saveAndFlush(MedRegistry.builder().regNumber("ZZQ-РУ-4242").name("ZZQ Монитор пациента прикроватный")
                .producer("ZZQ Медтех").unlimited(true).build());
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    static SiteProduct product(String name, String brand, String sku, OffsetDateTime removed) {
        return SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name(name).brand(brand).sku(sku)
                .slug(sku.toLowerCase()).removedAt(removed).syncedAt(OffsetDateTime.now()).build();
    }

    @Test
    void ours_allWordsInNameBrandOrSku_removedSkipped() {
        assertThat(search.search(lot.getId(), "zzq дки").ours()).extracting(LotModelSearchResponse.Our::name)
                .containsExactly("ZZQ Дефибриллятор ДКИ-Н-12");
        assertThat(search.search(lot.getId(), "аксион zzq-777").ours()).hasSize(1)
                .allSatisfy(o -> assertThat(o.url()).endsWith("zzq-777"));
        assertThat(search.search(lot.getId(), "zzq дефибриллятор").ours()).extracting(LotModelSearchResponse.Our::name)
                .containsExactly("ZZQ Дефибриллятор ДКИ-Н-12");
        assertThat(search.search(lot.getId(), "z").ours()).isEmpty();
        assertThat(search.search(lot.getId(), "z").registry()).isEmpty();
    }

    @Test
    void registry_exactNumberFirst_andWords() {
        assertThat(search.search(lot.getId(), "zzq-ру-4242").registry()).first()
                .satisfies(r -> {
                    assertThat(r.regNumber()).isEqualTo("ZZQ-РУ-4242");
                    assertThat(r.unlimited()).isTrue();
                    assertThat(r.expired()).isFalse();
                });
        assertThat(search.search(lot.getId(), "ZZQ монитор прикроватный").registry())
                .extracting(LotModelSearchResponse.Registry::regNumber).contains("ZZQ-РУ-4242");
    }

    @Test
    void otherMarketLot_404_rfLot_400() {
        TenderLot rf = LotModelFixtures.lot(tenders, Market.RF, "ZZQ РФ");
        assertThatThrownBy(() -> search.search(rf.getId(), "дки")).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> search.search(rf.getId(), "дки"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Только для Казахстана");
    }
}
