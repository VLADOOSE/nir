package com.vladoose.nir.repository;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderRepositoryGoszakupTest {

    @Autowired
    TenderRepository repository;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void findBySourceExtId_scopedByMarket() {
        MarketContext.set(Market.KZ);
        Tender t = Tender.builder()
                .tenderNumber("415500-1").sourceExtId("415500-1")
                .status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.KZ).currency("KZT")
                .region("г. Алматы").regionKato("750000000")
                .customerName("ГКП Поликлиника №5").customerBin("123456789012")
                .build();
        repository.save(t);

        Optional<Tender> kz = repository.findBySourceExtId("415500-1");
        assertThat(kz).isPresent();
        assertThat(kz.get().getRegion()).isEqualTo("г. Алматы");
        assertThat(kz.get().getCustomerName()).isEqualTo("ГКП Поликлиника №5");

        MarketContext.set(Market.RF);
        assertThat(repository.findBySourceExtId("415500-1")).isEmpty(); // изоляция рынка
    }
}
