package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.ApplyItemRequest;
import com.vladoose.nir.dto.response.ApplyItemResponse;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring",
        uses = {ActivityApplyMapper.class, TenderLotMapper.class,
                MedEquipmentMapper.class, DistributorMapper.class})
public interface ApplyItemMapper {

    @Mapping(target = "apply", source = "apply")
    @Mapping(target = "tenderLot", source = "tenderLot")
    @Mapping(target = "medEquipment", source = "medEquipment")
    @Mapping(target = "distributor", source = "distributor")
    ApplyItemResponse toResponse(ApplyItem entity);

    List<ApplyItemResponse> toResponseList(List<ApplyItem> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "apply", source = "applyId", qualifiedByName = "applyFromId")
    @Mapping(target = "tenderLot", source = "tenderLotId", qualifiedByName = "tenderLotFromId")
    @Mapping(target = "medEquipment", source = "medEquipId", qualifiedByName = "medEquipmentFromId")
    @Mapping(target = "distributor", source = "distributorId", qualifiedByName = "distributorFromId")
    ApplyItem toEntity(ApplyItemRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "apply", source = "applyId", qualifiedByName = "applyFromId")
    @Mapping(target = "tenderLot", source = "tenderLotId", qualifiedByName = "tenderLotFromId")
    @Mapping(target = "medEquipment", source = "medEquipId", qualifiedByName = "medEquipmentFromId")
    @Mapping(target = "distributor", source = "distributorId", qualifiedByName = "distributorFromId")
    void updateEntity(ApplyItemRequest request, @MappingTarget ApplyItem entity);

    @Named("applyFromId")
    default ActivityApply applyFromId(Long id) {
        if (id == null) return null;
        ActivityApply a = new ActivityApply();
        a.setId(id);
        return a;
    }

    @Named("tenderLotFromId")
    default TenderLot tenderLotFromId(Long id) {
        if (id == null) return null;
        TenderLot l = new TenderLot();
        l.setId(id);
        return l;
    }

    @Named("medEquipmentFromId")
    default MedEquipment medEquipmentFromId(Long id) {
        if (id == null) return null;
        MedEquipment m = new MedEquipment();
        m.setId(id);
        return m;
    }

    @Named("distributorFromId")
    default Distributor distributorFromId(Long id) {
        if (id == null) return null;
        Distributor d = new Distributor();
        d.setId(id);
        return d;
    }
}
