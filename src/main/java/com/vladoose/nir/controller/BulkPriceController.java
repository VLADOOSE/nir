package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.BulkPriceSendRequest;
import com.vladoose.nir.dto.response.BulkPricePreviewResponse;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.mapper.MedEquipmentMapper;
import com.vladoose.nir.mapper.TenderMapper;
import com.vladoose.nir.service.BulkPriceRequestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bulk-price")
public class BulkPriceController {

    private final BulkPriceRequestService service;
    private final DistributorMapper distributorMapper;
    private final MedEquipmentMapper medEquipmentMapper;
    private final TenderMapper tenderMapper;

    public BulkPriceController(BulkPriceRequestService service,
                               DistributorMapper distributorMapper,
                               MedEquipmentMapper medEquipmentMapper,
                               TenderMapper tenderMapper) {
        this.service = service;
        this.distributorMapper = distributorMapper;
        this.medEquipmentMapper = medEquipmentMapper;
        this.tenderMapper = tenderMapper;
    }

    @GetMapping("/preview/{tenderId}")
    public BulkPricePreviewResponse preview(@PathVariable Long tenderId) {
        var raw = service.buildPreview(tenderId);
        BulkPricePreviewResponse r = new BulkPricePreviewResponse();
        r.setLotsWithoutMatch(tenderMapper.lotToShortResponseList(raw.lotsWithoutMatch()));
        r.setLotsWithoutDistributor(tenderMapper.lotToShortResponseList(raw.lotsWithoutDistributor()));
        r.setGroups(raw.groups().stream().map(g -> {
            var dto = new BulkPricePreviewResponse.Group();
            dto.setDistributor(distributorMapper.toResponse(g.distributor()));
            dto.setItems(g.items().stream().map(it -> {
                var i = new BulkPricePreviewResponse.Item();
                i.setLot(tenderMapper.lotToShortResponse(it.lot()));
                i.setEquipment(medEquipmentMapper.toResponse(it.equipment()));
                i.setLotMaxCost(it.lot().getMaxCost());
                i.setExceedsBudget(it.exceedsBudget());
                return i;
            }).collect(Collectors.toList()));
            return dto;
        }).collect(Collectors.toList()));
        return r;
    }

    @PostMapping("/send")
    public Long send(@Valid @RequestBody BulkPriceSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new BulkPriceRequestService.SendItem(i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        var pr = service.sendGroup(req.getTenderId(), req.getDistributorId(), items);
        return pr.getId();
    }
}
