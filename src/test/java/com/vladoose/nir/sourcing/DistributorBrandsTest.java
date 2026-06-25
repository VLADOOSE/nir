package com.vladoose.nir.sourcing;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.repository.DistributorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DistributorBrandsTest {

    @Autowired DistributorRepository repository;

    @Test
    void brands_persistAndLoad() {
        Distributor d = Distributor.builder()
                .name("ZZBRANDS Поставщик")
                .brands(new java.util.ArrayList<>(List.of("Mindray", "Hamilton")))
                .build();
        Distributor saved = repository.save(d);
        repository.flush();

        Distributor loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getBrands()).containsExactlyInAnyOrder("Mindray", "Hamilton");
    }
}
