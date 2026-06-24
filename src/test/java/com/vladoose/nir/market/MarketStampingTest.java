package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.FacilityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MarketStampingTest {

    @Autowired FacilityService facilityService;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void create_stampsActiveMarket_KZ() {
        MarketContext.set(Market.KZ);
        Facility saved = facilityService.save(
                Facility.builder().name("ZZSTAMP-KZ учреждение").build());
        assertThat(saved.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void create_defaultsToRF_whenNoContext() {
        MarketContext.clear();
        Facility saved = facilityService.save(
                Facility.builder().name("ZZSTAMP-RF учреждение").build());
        assertThat(saved.getMarket()).isEqualTo(Market.RF);
    }

    @Test
    void update_preservesMarket_doesNotRestamp() {
        MarketContext.set(Market.KZ);
        Facility f = facilityService.save(Facility.builder().name("ZZUPD-KZ учреждение").build());
        assertThat(f.getMarket()).isEqualTo(Market.KZ);
        // переключаемся на RF и сохраняем существующую (id!=null) — рынок должен сохраниться KZ
        MarketContext.set(Market.RF);
        f.setAddress("обновлённый адрес");
        Facility updated = facilityService.save(f);
        assertThat(updated.getMarket()).isEqualTo(Market.KZ);
    }
}
