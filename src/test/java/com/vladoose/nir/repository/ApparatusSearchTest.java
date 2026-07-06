package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ApparatusRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Поиск аппаратов «(МТ)» по бренду из ТЗ — на живом реестре nirdb (ЭЛЭСКУЛАП зарегистрирован). */
@SpringBootTest
@Transactional
class ApparatusSearchTest {

    @Autowired MedRegistryRepository repo;

    @Test
    void findsElesculapApparatusByBrandTerm() {
        List<ApparatusRow> rows = repo.findApparatusByTerm("элэскулап", 3);
        assertThat(rows).isNotEmpty();
        assertThat(rows).anyMatch(r -> r.getRegNumber().contains("(МТ)")
                && r.getName().toLowerCase().contains("элэскулап"));
    }

    @Test
    void nonApparatusTerm_returnsEmpty() {
        // заведомо мусорный бренд-термин не должен тянуть аппараты
        assertThat(repo.findApparatusByTerm("zzzнеттакогобренда", 3)).isEmpty();
    }
}
