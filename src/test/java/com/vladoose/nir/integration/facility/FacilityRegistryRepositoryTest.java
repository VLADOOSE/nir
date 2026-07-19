package com.vladoose.nir.integration.facility;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.FacilityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FacilityRegistryRepositoryTest {

    @Autowired FacilityRepository repo;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void findsMonitoredZkoHospitalsFromSeed() {
        List<Facility> zko = repo.findByMarketAndRegionAndMonitorTendersTrue(Market.KZ, "Западно-Казахстанская область");
        assertThat(zko).hasSizeGreaterThanOrEqualTo(29);
        assertThat(zko).allMatch(Facility::isMonitorTenders);
        assertThat(zko).anyMatch(f -> "110340002524".equals(f.getInn()));
    }

    @Test
    void allMonitoredKzIncludesSeed_butRegionFilterNarrows() {
        assertThat(repo.findByMarketAndMonitorTendersTrue(Market.KZ)).hasSizeGreaterThanOrEqualTo(29);
        assertThat(repo.findByMarketAndRegionAndMonitorTendersTrue(Market.KZ, "Карагандинская область")).isEmpty();
    }
}
