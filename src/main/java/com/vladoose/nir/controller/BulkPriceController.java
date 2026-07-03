package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.BulkPriceSendRequest;
import com.vladoose.nir.dto.response.BulkPricePreviewResponse;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.mapper.MedEquipmentMapper;
import com.vladoose.nir.mapper.TenderMapper;
import com.vladoose.nir.service.BulkPriceRequestService;
import com.vladoose.nir.service.PriceRequestSendService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bulk-price")
public class BulkPriceController {

    private final BulkPriceRequestService service;
    private final DistributorMapper distributorMapper;
    private final MedEquipmentMapper medEquipmentMapper;
    private final TenderMapper tenderMapper;
    private final PriceRequestSendService sendService;

    public BulkPriceController(BulkPriceRequestService service,
                               DistributorMapper distributorMapper,
                               MedEquipmentMapper medEquipmentMapper,
                               TenderMapper tenderMapper,
                               PriceRequestSendService sendService) {
        this.service = service;
        this.distributorMapper = distributorMapper;
        this.medEquipmentMapper = medEquipmentMapper;
        this.tenderMapper = tenderMapper;
        this.sendService = sendService;
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
                return i;
            }).collect(Collectors.toList()));
            return dto;
        }).collect(Collectors.toList()));
        return r;
    }

    /** Тонкий делегат к единому каналу отправки (кандидат на удаление — фронт зовёт /api/price-requests/send). */
    @PostMapping("/send")
    public Long send(@Valid @RequestBody BulkPriceSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        var results = sendService.send(req.getTenderId(), List.of(req.getDistributorId()), items);
        return results.get(0).priceRequestId();
    }
}
