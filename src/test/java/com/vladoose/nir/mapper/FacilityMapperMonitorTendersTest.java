package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.FacilityRequest;
import com.vladoose.nir.entity.Facility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fix I1: partial PUT must not clobber monitorTenders.
 * FacilityRequest.monitorTenders is boxed Boolean → when a PUT omits it (null),
 * NullValuePropertyMappingStrategy.IGNORE on updateEntity preserves the entity's value.
 * Plain JUnit against the generated FacilityMapperImpl (no Spring context needed).
 */
class FacilityMapperMonitorTendersTest {

    private final FacilityMapper mapper = new FacilityMapperImpl();

    @Test
    void updateEntity_nullMonitorTenders_preservesExistingTrue() {
        Facility entity = Facility.builder().name("Больница №1").monitorTenders(true).build();
        FacilityRequest req = new FacilityRequest();
        req.setName("Больница №1 (переименована)");
        req.setMonitorTenders(null); // partial PUT omitted the flag

        mapper.updateEntity(req, entity);

        assertThat(entity.isMonitorTenders()).isTrue(); // the bug fix: NOT reset to false
        assertThat(entity.getName()).isEqualTo("Больница №1 (переименована)");
    }

    @Test
    void updateEntity_falseMonitorTenders_setsFalse() {
        Facility entity = Facility.builder().name("Больница №2").monitorTenders(true).build();
        FacilityRequest req = new FacilityRequest();
        req.setMonitorTenders(false);

        mapper.updateEntity(req, entity);

        assertThat(entity.isMonitorTenders()).isFalse();
    }

    @Test
    void updateEntity_trueMonitorTenders_setsTrue() {
        Facility entity = Facility.builder().name("Больница №3").monitorTenders(false).build();
        FacilityRequest req = new FacilityRequest();
        req.setMonitorTenders(true);

        mapper.updateEntity(req, entity);

        assertThat(entity.isMonitorTenders()).isTrue();
    }

    @Test
    void toEntity_nullMonitorTenders_doesNotThrow_andYieldsFalse() {
        FacilityRequest req = new FacilityRequest();
        req.setName("Новая клиника");
        req.setMonitorTenders(null);

        Facility entity = mapper.toEntity(req); // MapStruct must null-guard boxed→primitive

        assertThat(entity.isMonitorTenders()).isFalse();
    }

    @Test
    void toEntity_trueMonitorTenders_yieldsTrue() {
        FacilityRequest req = new FacilityRequest();
        req.setName("Мониторим-клиника");
        req.setMonitorTenders(true);

        Facility entity = mapper.toEntity(req);

        assertThat(entity.isMonitorTenders()).isTrue();
    }
}
