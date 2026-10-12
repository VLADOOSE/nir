package com.vladoose.nir.sourcing;

import com.vladoose.nir.util.BrandMatch;
import com.vladoose.nir.util.EmailAddresses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V28 (спека 2026-10-12 §3.3): пять производителей рынка KZ — отметка, почта, виды МИ и бренд, которым их товар приходит с
 * westmed.kz. Значения адресов — в самой миграции (их утвердил оператор); тест держит то, что не меняется правкой карточки.
 */
@SpringBootTest
class ManufacturersSeedTest {

    @Autowired JdbcTemplate jdbc;

    /** Название производителя → бренд товара на westmed.kz. */
    private static final Map<String, String> MAKERS = Map.of(
            "ООО «ФОТЕК»", "Фотек",
            "ЗАО «Завод ЭМА»", "Завод ЭМА",
            "ООО Концерн «Аксион»", "Аксион",
            "ООО «Орион-Си»", "Орион",
            "ООО «НПП «Монитор»»", "НПП Монитор");

    @Test
    void fiveManufacturersOnKzWithEmailTypesAndTheirWestmedBrand() {
        MAKERS.forEach((name, westmedBrand) -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT id, market, manufacturer, email FROM distributor WHERE name = ?", name);
            assertThat(row.get("market")).as(name).isEqualTo("KZ");
            assertThat(row.get("manufacturer")).as(name).isEqualTo(true);
            assertThat(EmailAddresses.parse((String) row.get("email"))).as(name).isNotEmpty();
            List<String> brands = jdbc.queryForList("SELECT brand FROM distributor_brand WHERE distributor_id = ?",
                    String.class, row.get("id"));
            assertThat(BrandMatch.firstCarried(brands, westmedBrand)).as(name + " ловит «" + westmedBrand + "»").isNotNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM distributor_equipment_type WHERE distributor_id = ?",
                    Integer.class, row.get("id"))).as(name).isPositive();
        });
    }

    @Test
    void manufacturerBrandsDoNotCatchForeignLookAlikes() {
        List<String> all = jdbc.queryForList("SELECT b.brand FROM distributor_brand b JOIN distributor d ON d.id = b.distributor_id "
                + "WHERE d.manufacturer AND d.name IN ('ООО «ФОТЕК»','ЗАО «Завод ЭМА»','ООО Концерн «Аксион»','ООО «Орион-Си»',"
                + "'ООО «НПП «Монитор»»')", String.class);
        assertThat(BrandMatch.firstCarried(all, "Orion Diagnostica")).isNull();
        assertThat(BrandMatch.firstCarried(all, "Hemasure")).isNull();
        assertThat(BrandMatch.firstCarried(all, "Mindray")).isNull();
    }
}
