package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.TenderLotRequest;
import com.vladoose.nir.dto.response.ProposedEquipmentResponse;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = {TenderMapper.class, EquipmentTypeMapper.class})
public interface TenderLotMapper {

    @Mapping(target = "tender", source = "tender")
    @Mapping(target = "productMatches", ignore = true)
    @Mapping(target = "requirements", ignore = true)
    @Mapping(target = "aiProductName", source = "aiProduct.name")
    TenderLotResponse toResponse(TenderLot entity);

    List<TenderLotResponse> toResponseList(List<TenderLot> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tender", source = "tenderId", qualifiedByName = "tenderFromId")
    @Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")
    @Mapping(target = "proposedEquipment", ignore = true)
    @Mapping(target = "modelNote", ignore = true)
    @Mapping(target = "modelSetBy", ignore = true)
    @Mapping(target = "modelSetAt", ignore = true)
    @Mapping(target = "aiVerdict", ignore = true)
    @Mapping(target = "aiProduct", ignore = true)
    @Mapping(target = "aiNote", ignore = true)
    @Mapping(target = "aiBy", ignore = true)
    @Mapping(target = "aiAt", ignore = true)
    @Mapping(target = "modelByAi", ignore = true)
    @Mapping(target = "extLotId", ignore = true)
    @Mapping(target = "planPointId", ignore = true)
    @Mapping(target = "enstruCode", ignore = true)
    @Mapping(target = "planUnit", ignore = true)
    TenderLot toEntity(TenderLotRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tender", source = "tenderId", qualifiedByName = "tenderFromId")
    @Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")
    @Mapping(target = "proposedEquipment", ignore = true)
    @Mapping(target = "modelNote", ignore = true)
    @Mapping(target = "modelSetBy", ignore = true)
    @Mapping(target = "modelSetAt", ignore = true)
    @Mapping(target = "aiVerdict", ignore = true)
    @Mapping(target = "aiProduct", ignore = true)
    @Mapping(target = "aiNote", ignore = true)
    @Mapping(target = "aiBy", ignore = true)
    @Mapping(target = "aiAt", ignore = true)
    @Mapping(target = "modelByAi", ignore = true)
    @Mapping(target = "extLotId", ignore = true)
    @Mapping(target = "planPointId", ignore = true)
    @Mapping(target = "enstruCode", ignore = true)
    @Mapping(target = "planUnit", ignore = true)
    void updateEntity(TenderLotRequest request, @MappingTarget TenderLot entity);

    /** MedEquipment → компактное DTO предложенной модели (подхватывается MapStruct по типам). */
    default ProposedEquipmentResponse proposedEquipmentToResponse(MedEquipment e) {
        if (e == null) return null;
        ProposedEquipmentResponse r = new ProposedEquipmentResponse();
        r.setId(e.getId());
        r.setName(e.getName());
        r.setManufact(e.getManufact());
        r.setRegistrationStatus(e.getRegistrationStatus() != null ? e.getRegistrationStatus().name() : null);
        r.setRegNumber(e.getRegistration() != null ? e.getRegistration().getRegNumber() : null);
        r.setSiteProductId(e.getSiteProduct() != null ? e.getSiteProduct().getId() : null);
        r.setSource(e.getSiteProduct() != null ? "SITE" : e.getRegistration() != null ? "REGISTRY" : "CATALOG");
        r.setRegExpires(e.getRegistration() != null ? e.getRegistration().getExpirationDate() : null);
        return r;
    }

    @Named("tenderFromId")
    default Tender tenderFromId(Long id) {
        if (id == null) return null;
        Tender t = new Tender();
        t.setId(id);
        return t;
    }

    @Named("equipmentTypeFromId")
    default EquipmentType equipmentTypeFromId(Long id) {
        if (id == null) return null;
        EquipmentType e = new EquipmentType();
        e.setId(id);
        return e;
    }
}
