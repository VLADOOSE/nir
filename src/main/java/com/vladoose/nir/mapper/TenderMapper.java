package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.TenderRequest;
import com.vladoose.nir.dto.response.TenderLotShortResponse;
import com.vladoose.nir.dto.response.TenderResponse;
import com.vladoose.nir.dto.response.TenderShortResponse;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring", uses = {FacilityMapper.class, EquipmentTypeMapper.class})
public interface TenderMapper {

    @Mapping(target = "facility", source = "facility")
    @Mapping(target = "lots", source = "lots")
    TenderResponse toResponse(Tender entity);

    List<TenderResponse> toResponseList(List<Tender> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "lots", ignore = true)
    @Mapping(target = "facility", source = "facilityId", qualifiedByName = "facilityFromId")
    @Mapping(target = "market", ignore = true)
    @Mapping(target = "source", ignore = true)
    Tender toEntity(TenderRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "lots", ignore = true)
    @Mapping(target = "facility", source = "facilityId", qualifiedByName = "facilityFromId")
    @Mapping(target = "market", ignore = true)
    @Mapping(target = "source", ignore = true)
    void updateEntity(TenderRequest request, @MappingTarget Tender entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "tenderNumber", source = "tenderNumber")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "deadline", source = "deadline")
    TenderShortResponse toShortResponse(Tender entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "lotNumber", source = "lotNumber")
    @Mapping(target = "equipName", source = "equipName")
    @Mapping(target = "manufact", source = "manufact")
    @Mapping(target = "equipmentType", source = "equipmentType")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "maxCost", source = "maxCost")
    TenderLotShortResponse lotToShortResponse(TenderLot lot);

    List<TenderLotShortResponse> lotToShortResponseList(List<TenderLot> lots);

    @Named("facilityFromId")
    default Facility facilityFromId(Long id) {
        if (id == null) return null;
        Facility f = new Facility();
        f.setId(id);
        return f;
    }
}
