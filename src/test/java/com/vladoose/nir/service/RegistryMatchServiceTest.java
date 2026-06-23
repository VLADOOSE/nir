package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.RegistrationAction;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RegistryMatchServiceTest {

    @Autowired RegistryMatchService service;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired MedEquipmentRepository equipmentRepository;

    private MedEquipment newEquipment() {
        return equipmentRepository.save(MedEquipment.builder()
                .name("Тонометр ТЕСТМАТЧ УникальныйZZ").manufact("ZZMATCHVENDOR-Uniq")
                .registrationStatus(RegistrationStatus.UNCHECKED)
                .build());
    }

    @Test
    void confirm_setsRegisteredAndLinksRegistry() {
        registryRepository.save(MedRegistry.builder()
                .regNumber("ZZMATCH-RU-1").name("Тонометр ТЕСТМАТЧ УникальныйZZ")
                .producer("ZZMATCHVENDOR-Uniq").country("ЯПОНИЯ").unlimited(true).build());
        MedEquipment e = newEquipment();

        MedEquipment updated = service.applyAction(e.getId(), RegistrationAction.CONFIRM, "ZZMATCH-RU-1");

        assertThat(updated.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(updated.getRegistration()).isNotNull();
        assertThat(updated.getRegistration().getRegNumber()).isEqualTo("ZZMATCH-RU-1");
        assertThat(updated.getRegistrationCheckedAt()).isNotNull();
    }

    @Test
    void confirm_withUnknownRegNumber_throwsBadRequest() {
        MedEquipment e = newEquipment();
        assertThatThrownBy(() -> service.applyAction(e.getId(), RegistrationAction.CONFIRM, "НЕТ-ТАКОГО"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void confirm_withBlankRegNumber_throwsBadRequest() {
        MedEquipment e = newEquipment();
        assertThatThrownBy(() -> service.applyAction(e.getId(), RegistrationAction.CONFIRM, ""))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void markNotMedical_thenReset_changesStatus() {
        MedEquipment e = newEquipment();

        MedEquipment notMed = service.applyAction(e.getId(), RegistrationAction.NOT_MEDICAL, null);
        assertThat(notMed.getRegistrationStatus()).isEqualTo(RegistrationStatus.NOT_MEDICAL);
        assertThat(notMed.getRegistration()).isNull();
        assertThat(notMed.getRegistrationCheckedAt()).isNotNull();

        MedEquipment reset = service.applyAction(e.getId(), RegistrationAction.RESET, null);
        assertThat(reset.getRegistrationStatus()).isEqualTo(RegistrationStatus.UNCHECKED);
        assertThat(reset.getRegistrationCheckedAt()).isNull();
    }

    @Test
    void candidatesForEquipment_returnsTrgmMatch() {
        registryRepository.save(MedRegistry.builder()
                .regNumber("ZZMATCH-RU-2").name("Тонометр ТЕСТМАТЧ УникальныйZZ")
                .producer("ZZMATCHVENDOR-Uniq").country("ЯПОНИЯ").unlimited(true).build());
        MedEquipment e = newEquipment();

        var candidates = service.candidatesForEquipment(e.getId(), 5);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).getRegNumber()).isEqualTo("ZZMATCH-RU-2");
        assertThat(candidates.get(0).getScore()).isGreaterThan(0.0);
    }
}
