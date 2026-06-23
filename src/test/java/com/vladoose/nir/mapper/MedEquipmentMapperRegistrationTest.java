package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.response.MedEquipmentResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MedEquipmentMapperRegistrationTest {

    @Autowired
    MedEquipmentMapper mapper;

    @Test
    void registeredEquipment_mapsToVatExemptWithRegistryDetails() {
        MedRegistry reg = MedRegistry.builder()
                .regNumber("РК МИ-TEST").producer("OMRON").country("ЯПОНИЯ").unlimited(true)
                .build();
        MedEquipment e = MedEquipment.builder()
                .name("Тонометр OMRON M2").manufact("OMRON")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registration(reg)
                .build();

        MedEquipmentResponse resp = mapper.toResponse(e);

        assertThat(resp.getRegistration()).isNotNull();
        assertThat(resp.getRegistration().getStatus()).isEqualTo("REGISTERED");
        assertThat(resp.getRegistration().isVatExempt()).isTrue();
        assertThat(resp.getRegistration().getRegNumber()).isEqualTo("РК МИ-TEST");
        assertThat(resp.getRegistration().getProducer()).isEqualTo("OMRON");
    }

    @Test
    void uncheckedEquipment_mapsToNotVatExemptWithoutDetails() {
        MedEquipment e = MedEquipment.builder()
                .name("Стол офисный").manufact("ИКЕА")
                .registrationStatus(RegistrationStatus.UNCHECKED)
                .build();

        MedEquipmentResponse resp = mapper.toResponse(e);

        assertThat(resp.getRegistration().getStatus()).isEqualTo("UNCHECKED");
        assertThat(resp.getRegistration().isVatExempt()).isFalse();
        assertThat(resp.getRegistration().getRegNumber()).isNull();
    }
}
