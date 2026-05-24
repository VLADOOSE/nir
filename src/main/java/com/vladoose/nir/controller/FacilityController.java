package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.service.FacilityService;
import jakarta.validation.Valid;
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
    public Facility create(@Valid @RequestBody Facility facility) {
        return service.save(facility);
    }

    @PutMapping("/{id}")
    public Facility update(@PathVariable Long id, @Valid @RequestBody Facility facility) {
        Facility existing = service.findById(id);
        existing.setName(facility.getName());
        existing.setInn(facility.getInn());
        existing.setAddress(facility.getAddress());
        existing.setLastName(facility.getLastName());
        existing.setFirstName(facility.getFirstName());
        existing.setMiddleName(facility.getMiddleName());
        existing.setPhone(facility.getPhone());
        existing.setEmail(facility.getEmail());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
