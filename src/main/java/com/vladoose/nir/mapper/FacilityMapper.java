package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.FacilityRequest;
import com.vladoose.nir.dto.response.FacilityResponse;
import com.vladoose.nir.entity.Facility;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring")
public interface FacilityMapper {

    FacilityResponse toResponse(Facility entity);

    List<FacilityResponse> toResponseList(List<Facility> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "market", ignore = true)
    Facility toEntity(FacilityRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "market", ignore = true)
    void updateEntity(FacilityRequest request, @MappingTarget Facility entity);
}
