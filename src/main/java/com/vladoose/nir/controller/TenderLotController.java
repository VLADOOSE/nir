package com.vladoose.nir.controller;

import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.TenderLotService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lots")
public class TenderLotController {

    private final TenderLotService service;

    public TenderLotController(TenderLotService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public TenderLot findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public TenderLot create(@RequestBody TenderLot lot) {
        return service.save(lot);
    }

    @PutMapping("/{id}")
    public TenderLot update(@PathVariable Long id, @RequestBody TenderLot lot) {
        TenderLot existing = service.findById(id);
        existing.setTender(lot.getTender());
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
