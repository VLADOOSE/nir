package com.vladoose.nir.controller;

import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.*;
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

    public ApplyItemController(ApplyItemService service,
                               ActivityApplyService activityApplyService,
                               TenderLotService tenderLotService,
                               MedEquipmentService medEquipmentService,
                               DistributorService distributorService) {
        this.service = service;
        this.activityApplyService = activityApplyService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
    }

    @GetMapping("/{id}")
    public ApplyItem findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public ApplyItem create(@Valid @RequestBody ApplyItem item) {
        if (item.getApply() == null || item.getApply().getId() == null) {
            throw new BadRequestException("Не указана заявка");
        }
        item.setApply(activityApplyService.findById(item.getApply().getId()));
        if (item.getTenderLot() != null && item.getTenderLot().getId() != null) {
            item.setTenderLot(tenderLotService.findById(item.getTenderLot().getId()));
        }
        if (item.getMedEquipment() != null && item.getMedEquipment().getId() != null) {
            item.setMedEquipment(medEquipmentService.findById(item.getMedEquipment().getId()));
        }
        if (item.getDistributor() != null && item.getDistributor().getId() != null) {
            item.setDistributor(distributorService.findById(item.getDistributor().getId()));
        }
        return service.save(item);
    }

    @PutMapping("/{id}")
    public ApplyItem update(@PathVariable Long id, @Valid @RequestBody ApplyItem item) {
        ApplyItem existing = service.findById(id);
        if (item.getTenderLot() != null && item.getTenderLot().getId() != null) {
            existing.setTenderLot(tenderLotService.findById(item.getTenderLot().getId()));
        }
        if (item.getMedEquipment() != null && item.getMedEquipment().getId() != null) {
            existing.setMedEquipment(medEquipmentService.findById(item.getMedEquipment().getId()));
        }
        if (item.getDistributor() != null && item.getDistributor().getId() != null) {
            existing.setDistributor(distributorService.findById(item.getDistributor().getId()));
        }
        existing.setOfferedCost(item.getOfferedCost());
        existing.setQuantity(item.getQuantity());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
