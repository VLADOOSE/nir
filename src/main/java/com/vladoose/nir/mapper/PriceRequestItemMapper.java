package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.PriceRequestItemRequest;
import com.vladoose.nir.dto.response.PriceRequestItemResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.TenderLot;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = {TenderLotMapper.class, MedEquipmentMapper.class, TenderMapper.class})
public interface PriceRequestItemMapper {

    @Mapping(target = "tenderLot", source = "tenderLot", qualifiedByName = "lotShortFromEntity")
    PriceRequestItemResponse toResponse(PriceRequestItem entity);

    List<PriceRequestItemResponse> toResponseList(List<PriceRequestItem> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "priceRequest", ignore = true)
    @Mapping(target = "tenderLot", source = "tenderLotId", qualifiedByName = "lotFromIdStub")
    @Mapping(target = "medEquipment", source = "medEquipmentId", qualifiedByName = "medFromIdStub")
    PriceRequestItem toEntity(PriceRequestItemRequest request);

    @Named("lotShortFromEntity")
    default com.vladoose.nir.dto.response.TenderLotShortResponse lotShortFromEntity(TenderLot lot) {
        if (lot == null) return null;
        com.vladoose.nir.dto.response.TenderLotShortResponse r = new com.vladoose.nir.dto.response.TenderLotShortResponse();
        r.setId(lot.getId());
        r.setLotNumber(lot.getLotNumber());
        r.setEquipName(lot.getEquipName());
        r.setQuantity(lot.getQuantity());
        r.setMaxCost(lot.getMaxCost());
        if (lot.getEquipmentType() != null) {
            com.vladoose.nir.dto.response.EquipmentTypeResponse et = new com.vladoose.nir.dto.response.EquipmentTypeResponse();
            et.setId(lot.getEquipmentType().getId());
            et.setName(lot.getEquipmentType().getName());
            r.setEquipmentType(et);
        }
        return r;
    }

    @Named("lotFromIdStub")
    default TenderLot lotFromIdStub(Long id) {
        if (id == null) return null;
        TenderLot l = new TenderLot();
        l.setId(id);
        return l;
    }

    @Named("medFromIdStub")
    default MedEquipment medFromIdStub(Long id) {
        if (id == null) return null;
        MedEquipment m = new MedEquipment();
        m.setId(id);
        return m;
    }
}
