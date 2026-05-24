package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.PriceRequestRequest;
import com.vladoose.nir.dto.response.PriceRequestResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.Tender;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring",
        uses = {TenderMapper.class, DistributorMapper.class, PriceRequestItemMapper.class})
public interface PriceRequestMapper {

    @Mapping(target = "tender", source = "tender", qualifiedByName = "tenderShort")
    PriceRequestResponse toResponse(PriceRequest entity);

    List<PriceRequestResponse> toResponseList(List<PriceRequest> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tender", source = "tenderId", qualifiedByName = "tenderFromId")
    @Mapping(target = "distributor", source = "distributorId", qualifiedByName = "distributorFromId")
    @Mapping(target = "items", ignore = true)
    PriceRequest toEntity(PriceRequestRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tender", ignore = true)
    @Mapping(target = "distributor", ignore = true)
    @Mapping(target = "items", ignore = true)
    void updateEntity(PriceRequestRequest request, @MappingTarget PriceRequest entity);

    @Named("tenderShort")
    default com.vladoose.nir.dto.response.TenderShortResponse tenderShort(Tender t) {
        if (t == null) return null;
        com.vladoose.nir.dto.response.TenderShortResponse r = new com.vladoose.nir.dto.response.TenderShortResponse();
        r.setId(t.getId());
        r.setTenderNumber(t.getTenderNumber());
        r.setStatus(t.getStatus());
        r.setDeadline(t.getDeadline());
        return r;
    }

    @Named("tenderFromId")
    default Tender tenderFromId(Long id) {
        if (id == null) return null;
        Tender t = new Tender();
        t.setId(id);
        return t;
    }

    @Named("distributorFromId")
    default Distributor distributorFromId(Long id) {
        if (id == null) return null;
        Distributor d = new Distributor();
        d.setId(id);
        return d;
    }
}
