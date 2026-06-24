package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.ActivityApplyRequest;
import com.vladoose.nir.dto.response.ActivityApplyResponse;
import com.vladoose.nir.dto.response.ApplyShortResponse;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.Tender;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring", uses = {TenderMapper.class})
public interface ActivityApplyMapper {

    @Mapping(target = "tender", source = "tender")
    @Mapping(target = "items", ignore = true) // frontend loads via /applies/{id}/items
    ActivityApplyResponse toResponse(ActivityApply entity);

    List<ActivityApplyResponse> toResponseList(List<ActivityApply> entities);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "status", source = "status")
    ApplyShortResponse toShortResponse(ActivityApply entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "items", ignore = true)
    @Mapping(target = "tender", source = "tenderId", qualifiedByName = "tenderFromId")
    @Mapping(target = "market", ignore = true)
    ActivityApply toEntity(ActivityApplyRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "items", ignore = true)
    @Mapping(target = "tender", source = "tenderId", qualifiedByName = "tenderFromId")
    @Mapping(target = "market", ignore = true)
    void updateEntity(ActivityApplyRequest request, @MappingTarget ActivityApply entity);

    @Named("tenderFromId")
    default Tender tenderFromId(Long id) {
        if (id == null) return null;
        Tender t = new Tender();
        t.setId(id);
        return t;
    }
}
