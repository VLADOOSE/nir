package com.vladoose.nir.mapper;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderPlatformTest {

    @Autowired TenderMapper mapper;
    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void platform_persists_andMapsToResponseString() {
        MarketContext.set(Market.KZ);
        Facility f = facilityRepository.save(Facility.builder().name("P-" + UUID.randomUUID()).build());
        Tender t = tenderRepository.save(Tender.builder()
                .tenderNumber("363780-1").facility(f).status("ACTIVE")
                .platform(TenderPlatform.SK_PHARMACY).build());

        Tender reloaded = tenderRepository.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getPlatform()).isEqualTo(TenderPlatform.SK_PHARMACY);
        assertThat(mapper.toResponse(reloaded).getPlatform()).isEqualTo("SK_PHARMACY");
    }

    @Test
    void toPlatform_blankAndInvalid_null() {
        assertThat(mapper.toPlatform("SK_PHARMACY")).isEqualTo(TenderPlatform.SK_PHARMACY);
        assertThat(mapper.toPlatform("GOSZAKUP")).isEqualTo(TenderPlatform.GOSZAKUP);
        assertThat(mapper.toPlatform("")).isNull();
        assertThat(mapper.toPlatform("   ")).isNull();
        assertThat(mapper.toPlatform("BOGUS")).isNull();
        assertThat(mapper.toPlatform(null)).isNull();
    }
}
