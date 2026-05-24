package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.EquipmentTypeRequest;
import com.vladoose.nir.dto.response.EquipmentTypeResponse;
import com.vladoose.nir.entity.EquipmentType;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EquipmentTypeMapper {
    EquipmentTypeResponse toResponse(EquipmentType entity);
    List<EquipmentTypeResponse> toResponseList(List<EquipmentType> list);
    EquipmentType toEntity(EquipmentTypeRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(EquipmentTypeRequest request, @MappingTarget EquipmentType entity);
}
