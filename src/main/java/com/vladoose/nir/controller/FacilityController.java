package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.service.FacilityService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService service;

    public FacilityController(FacilityService service) {
        this.service = service;
    }

    @GetMapping
    public List<Facility> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Facility findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public Facility create(@RequestBody Facility facility) {
        return service.save(facility);
    }

    @PutMapping("/{id}")
    public Facility update(@PathVariable Long id, @RequestBody Facility facility) {
        Facility existing = service.findById(id);
        existing.setName(facility.getName());
        existing.setInn(facility.getInn());
        existing.setAddress(facility.getAddress());
        existing.setContact(facility.getContact());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
