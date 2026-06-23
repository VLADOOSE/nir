package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.MedEquipmentRequest;
import com.vladoose.nir.dto.response.EquipmentRegistrationResponse;
import com.vladoose.nir.dto.response.MedEquipmentResponse;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = EquipmentTypeMapper.class)
public interface MedEquipmentMapper {

    MedEquipmentResponse toResponse(MedEquipment entity);

    List<MedEquipmentResponse> toResponseList(List<MedEquipment> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")
    @Mapping(target = "registrationStatus", ignore = true)
    @Mapping(target = "registration", ignore = true)
    @Mapping(target = "registrationCheckedAt", ignore = true)
    MedEquipment toEntity(MedEquipmentRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")
    @Mapping(target = "registrationStatus", ignore = true)
    @Mapping(target = "registration", ignore = true)
    @Mapping(target = "registrationCheckedAt", ignore = true)
    void updateEntity(MedEquipmentRequest request, @MappingTarget MedEquipment entity);

    @Named("equipmentTypeFromId")
    default EquipmentType equipmentTypeFromId(Long id) {
        if (id == null) return null;
        EquipmentType e = new EquipmentType();
        e.setId(id);
        return e;
    }

    @AfterMapping
    default void fillRegistration(MedEquipment entity, @MappingTarget MedEquipmentResponse response) {
        // registration is always present (a status object); detail fields stay null unless REGISTERED
        EquipmentRegistrationResponse r = new EquipmentRegistrationResponse();
        RegistrationStatus status = entity.getRegistrationStatus() != null
                ? entity.getRegistrationStatus() : RegistrationStatus.UNCHECKED;
        r.setStatus(status.name());
        r.setVatExempt(status == RegistrationStatus.REGISTERED);
        r.setCheckedAt(entity.getRegistrationCheckedAt());
        MedRegistry reg = entity.getRegistration();
        if (reg != null) {
            r.setRegNumber(reg.getRegNumber());
            r.setProducer(reg.getProducer());
            r.setCountry(reg.getCountry());
            r.setRegDate(reg.getRegDate());
            r.setExpirationDate(reg.getExpirationDate());
            r.setUnlimited(reg.getUnlimited());
        }
        response.setRegistration(r);
    }
}
