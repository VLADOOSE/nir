package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.MedEquipmentRequest;
import com.vladoose.nir.dto.response.MedEquipmentResponse;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.MedEquipment;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = EquipmentTypeMapper.class)
public interface MedEquipmentMapper {

    MedEquipmentResponse toResponse(MedEquipment entity);

    List<MedEquipmentResponse> toResponseList(List<MedEquipment> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")
    MedEquipment toEntity(MedEquipmentRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")
    void updateEntity(MedEquipmentRequest request, @MappingTarget MedEquipment entity);

    @Named("equipmentTypeFromId")
    default EquipmentType equipmentTypeFromId(Long id) {
        if (id == null) return null;
        EquipmentType e = new EquipmentType();
        e.setId(id);
        return e;
    }
}
