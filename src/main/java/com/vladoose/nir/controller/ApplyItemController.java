package com.vladoose.nir.controller;

import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.service.ApplyItemService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apply-items")
public class ApplyItemController {

    private final ApplyItemService service;

    public ApplyItemController(ApplyItemService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ApplyItem findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public ApplyItem create(@RequestBody ApplyItem item) {
        return service.save(item);
    }

    @PutMapping("/{id}")
    public ApplyItem update(@PathVariable Long id, @RequestBody ApplyItem item) {
        ApplyItem existing = service.findById(id);
        existing.setApply(item.getApply());
        existing.setTenderLot(item.getTenderLot());
        existing.setMedEquipment(item.getMedEquipment());
        existing.setDistributor(item.getDistributor());
        existing.setOfferedCost(item.getOfferedCost());
        existing.setQuantity(item.getQuantity());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
