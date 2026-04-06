package com.vladoose.nir.controller;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.TenderLotService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment")
public class MedEquipmentController {

    private final MedEquipmentService service;
    private final TenderLotService tenderLotService;

    public MedEquipmentController(MedEquipmentService service, TenderLotService tenderLotService) {
        this.service = service;
        this.tenderLotService = tenderLotService;
    }

    @GetMapping
    public List<MedEquipment> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public MedEquipment findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public MedEquipment create(@RequestBody MedEquipment equipment) {
        return service.save(equipment);
    }

    @PutMapping("/{id}")
    public MedEquipment update(@PathVariable Long id, @RequestBody MedEquipment equipment) {
        MedEquipment existing = service.findById(id);
        existing.setName(equipment.getName());
        existing.setManufact(equipment.getManufact());
        existing.setEquipType(equipment.getEquipType());
        existing.setCost(equipment.getCost());
        existing.setLengthMm(equipment.getLengthMm());
        existing.setWidthMm(equipment.getWidthMm());
        existing.setHeightMm(equipment.getHeightMm());
        existing.setWeightKg(equipment.getWeightKg());
        existing.setSpec(equipment.getSpec());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    @GetMapping("/match/{lotId}")
    public List<MedEquipment> findMatchingForLot(@PathVariable Long lotId) {
        TenderLot lot = tenderLotService.findById(lotId);
        return service.findMatchingForLot(lot);
    }
}
