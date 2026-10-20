package com.vladoose.nir.lotmodel;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.mapper.TenderLotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** «Модель в работе» (спека 2026-10-20-lot-model-in-work §4): V40 и источник модели в ответе лота. */
@SpringBootTest
class LotModelSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenderLotMapper mapper;

    @Test
    void v40Columns() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'tender_lot' "
                + "and column_name like 'model\\_%'", String.class)).containsExactlyInAnyOrder("model_note", "model_set_by", "model_set_at");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'med_equipment' "
                + "and column_name = 'site_product_id'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'uq_med_equipment_site_product'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void sourceOfModel() {
        SiteProduct sp = SiteProduct.builder().id(7L).name("Дефибриллятор ДКИ-Н-12").build();
        MedRegistry reg = MedRegistry.builder().regNumber("РК-1").name("x").expirationDate(LocalDate.of(2030, 1, 1)).build();
        assertThat(mapper.proposedEquipmentToResponse(MedEquipment.builder().name("a").siteProduct(sp).registration(reg).build()))
                .satisfies(r -> {
                    assertThat(r.getSource()).isEqualTo("SITE");
                    assertThat(r.getSiteProductId()).isEqualTo(7L);
                    assertThat(r.getRegExpires()).isEqualTo(LocalDate.of(2030, 1, 1));
                });
        assertThat(mapper.proposedEquipmentToResponse(MedEquipment.builder().name("b").registration(reg).build()).getSource())
                .isEqualTo("REGISTRY");
        assertThat(mapper.proposedEquipmentToResponse(MedEquipment.builder().name("c").build()).getSource()).isEqualTo("CATALOG");
    }
}
