package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ApplyItemRequest;
import com.vladoose.nir.dto.response.ApplyItemResponse;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.ApplyItemMapper;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.ApplyItemService;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.TenderLotService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apply-items")
public class ApplyItemController {

    private final ApplyItemService service;
    private final ActivityApplyService activityApplyService;
    private final TenderLotService tenderLotService;
    private final MedEquipmentService medEquipmentService;
    private final DistributorService distributorService;
    private final ApplyItemMapper mapper;

    public ApplyItemController(ApplyItemService service,
                               ActivityApplyService activityApplyService,
                               TenderLotService tenderLotService,
                               MedEquipmentService medEquipmentService,
                               DistributorService distributorService,
                               ApplyItemMapper mapper) {
        this.service = service;
        this.activityApplyService = activityApplyService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.mapper = mapper;
    }

    @GetMapping("/{id}")
    public ApplyItemResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    public ApplyItemResponse create(@Valid @RequestBody ApplyItemRequest request) {
        if (request.getApplyId() == null) {
            throw new BadRequestException("Не указана заявка");
        }
        ApplyItem item = mapper.toEntity(request);
        item.setApply(activityApplyService.findById(request.getApplyId()));
        if (request.getTenderLotId() != null) {
            item.setTenderLot(tenderLotService.findById(request.getTenderLotId()));
        }
        if (request.getMedEquipId() != null) {
            item.setMedEquipment(medEquipmentService.findById(request.getMedEquipId()));
        }
        if (request.getDistributorId() != null) {
            item.setDistributor(distributorService.findById(request.getDistributorId()));
        }
        return mapper.toResponse(service.save(item));
    }

    @PutMapping("/{id}")
    public ApplyItemResponse update(@PathVariable Long id, @Valid @RequestBody ApplyItemRequest request) {
        ApplyItem existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getTenderLotId() != null) {
            existing.setTenderLot(tenderLotService.findById(request.getTenderLotId()));
        }
        if (request.getMedEquipId() != null) {
            existing.setMedEquipment(medEquipmentService.findById(request.getMedEquipId()));
        }
        if (request.getDistributorId() != null) {
            existing.setDistributor(distributorService.findById(request.getDistributorId()));
        }
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
