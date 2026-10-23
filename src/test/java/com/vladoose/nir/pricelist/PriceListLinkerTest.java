package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.pricelist.PriceListLinker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PriceListLinkerTest {

    @Autowired PriceListLinker linker;
    @Autowired SiteProductRepository products;
    @Autowired MedEquipmentRepository equipment;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private SiteProduct product(String name) {
        return products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name(name)
                .slug("zz-" + System.nanoTime()).syncedAt(OffsetDateTime.now()).build());
    }

    private static PriceListItem row(String name) {
        return PriceListItem.builder().name(name).build();
    }

    @Test
    void uniqueSiteProduct_linked_ambiguous_not_catalogFallback() {
        SiteProduct dki = product("ZZ Дефибриллятор ДКИ-Н-99 «Аксион»");
        PriceListItem a = row("Дефибриллятор ДКИ-Н-99. Базовые функции");
        linker.link(a, Market.KZ);
        assertThat(a.getModel()).isEqualTo("ДКИ-Н-99");
        assertThat(a.getModelKey()).isEqualTo("дкин99");
        assertThat(a.getSiteProductId()).isEqualTo(dki.getId());

        product("ZZ Отсасыватель ОМП-9/90-01 со стойкой");
        product("ZZ Отсасыватель ОМП-9/90-01 без стойки");
        PriceListItem b = row("Отсасыватель портативный ОМП-9/90-01");
        linker.link(b, Market.KZ);
        assertThat(b.getSiteProductId()).isNull();                      // два наших товара — связь ставит человек

        MedEquipment e = equipment.saveAndFlush(MedEquipment.builder().name("ZZ Тренажёр ДА-Н-Т-99").manufact("ZZ").market(Market.KZ).build());
        PriceListItem c = row("Тренажер ДА-Н-Т-99");
        linker.link(c, Market.KZ);
        assertThat(c.getSiteProductId()).isNull();
        assertThat(c.getMedEquipmentId()).isEqualTo(e.getId());
    }
}
