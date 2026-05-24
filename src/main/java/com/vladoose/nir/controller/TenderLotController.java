package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lots")
public class TenderLotController {

    private final TenderLotService service;
    private final TenderService tenderService;

    public TenderLotController(TenderLotService service, TenderService tenderService) {
        this.service = service;
        this.tenderService = tenderService;
    }

    @GetMapping("/{id}")
    public TenderLot findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public TenderLot create(@Valid @RequestBody TenderLot lot) {
        if (lot.getTender() == null || lot.getTender().getId() == null) {
            throw new BadRequestException("Не указан тендер");
        }
        Tender tender = tenderService.findById(lot.getTender().getId());
        lot.setTender(tender);
        return service.save(lot);
    }

    @PutMapping("/{id}")
    public TenderLot update(@PathVariable Long id, @Valid @RequestBody TenderLot lot) {
        TenderLot existing = service.findById(id);
        if (lot.getTender() != null && lot.getTender().getId() != null) {
            Tender tender = tenderService.findById(lot.getTender().getId());
            existing.setTender(tender);
        }
        existing.setLotNumber(lot.getLotNumber());
        existing.setEquipName(lot.getEquipName());
        existing.setEquipType(lot.getEquipType());
        existing.setQuantity(lot.getQuantity());
        existing.setMaxCost(lot.getMaxCost());
        existing.setMaxLengthMm(lot.getMaxLengthMm());
        existing.setMaxWidthMm(lot.getMaxWidthMm());
        existing.setMaxHeightMm(lot.getMaxHeightMm());
        existing.setMaxWeightKg(lot.getMaxWeightKg());
        existing.setRequiredSpec(lot.getRequiredSpec());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
