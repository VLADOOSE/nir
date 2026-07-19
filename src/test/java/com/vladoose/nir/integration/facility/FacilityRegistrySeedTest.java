package com.vladoose.nir.integration.facility;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FacilityRegistrySeedTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void v13_seeds29ZkoHospitals_withBinAndMonitorFlag() {
        Integer monitored = jdbc.queryForObject(
                "SELECT count(*) FROM facility WHERE market='KZ' AND monitor_tenders = true " +
                "AND region = 'Западно-Казахстанская область'", Integer.class);
        assertThat(monitored).isGreaterThanOrEqualTo(29);

        // БИН лежит в inn и это 12 цифр
        Integer bins = jdbc.queryForObject(
                "SELECT count(*) FROM facility WHERE market='KZ' AND monitor_tenders = true " +
                "AND inn ~ '^[0-9]{12}$'", Integer.class);
        assertThat(bins).isGreaterThanOrEqualTo(29);

        // конкретная больница из списка присутствует
        Integer detsk = jdbc.queryForObject(
                "SELECT count(*) FROM facility WHERE inn = '110340002524' AND market='KZ'", Integer.class);
        assertThat(detsk).isEqualTo(1);
    }
}
