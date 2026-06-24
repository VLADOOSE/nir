package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.repository.FacilityRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MarketScopingTest {

    @Autowired FacilityRepository facilityRepository;
    @Autowired EntityManager entityManager;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private Facility facility(String name, Market m) {
        return Facility.builder().name(name).market(m).build();
    }

    private void enableFilter(Market m) {
        // Источник истины для фильтра — MarketContext (его читает MarketFilterAspect).
        MarketContext.set(m);
        entityManager.unwrap(Session.class)
                .enableFilter("marketFilter").setParameter("market", m.name());
    }

    @Test
    void readsAreScopedByMarket() {
        facilityRepository.save(facility("ZZMK-RF учреждение", Market.RF));
        facilityRepository.save(facility("ZZMK-KZ учреждение", Market.KZ));
        facilityRepository.flush();
        entityManager.clear();

        enableFilter(Market.KZ);
        var kz = facilityRepository.findAll().stream()
                .filter(f -> f.getName().startsWith("ZZMK-")).toList();
        assertThat(kz).extracting(Facility::getName).containsExactly("ZZMK-KZ учреждение");

        entityManager.unwrap(Session.class).disableFilter("marketFilter");
        entityManager.clear();
        enableFilter(Market.RF);
        var rf = facilityRepository.findAll().stream()
                .filter(f -> f.getName().startsWith("ZZMK-")).toList();
        assertThat(rf).extracting(Facility::getName).containsExactly("ZZMK-RF учреждение");
    }
}
