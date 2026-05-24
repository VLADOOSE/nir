package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.DistributorRequest;
import com.vladoose.nir.dto.response.DistributorResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.EquipmentType;
import org.mapstruct.*;

import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring", uses = EquipmentTypeMapper.class)
public interface DistributorMapper {

    DistributorResponse toResponse(Distributor entity);

    List<DistributorResponse> toResponseList(List<Distributor> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "equipmentTypes", source = "equipmentTypeIds", qualifiedByName = "equipmentTypesFromIds")
    Distributor toEntity(DistributorRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "equipmentTypes", source = "equipmentTypeIds", qualifiedByName = "equipmentTypesFromIds")
    void updateEntity(DistributorRequest request, @MappingTarget Distributor entity);

    @Named("equipmentTypesFromIds")
    default List<EquipmentType> equipmentTypesFromIds(List<Long> ids) {
        if (ids == null) return new ArrayList<>();
        return ids.stream().map(id -> {
            EquipmentType e = new EquipmentType();
            e.setId(id);
            return e;
        }).toList();
    }
}
