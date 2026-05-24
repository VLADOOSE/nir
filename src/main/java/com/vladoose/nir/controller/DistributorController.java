package com.vladoose.nir.controller;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.service.DistributorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/distributors")
public class DistributorController {

    private final DistributorService service;

    public DistributorController(DistributorService service) {
        this.service = service;
    }

    @GetMapping
    public List<Distributor> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Distributor findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public Distributor create(@Valid @RequestBody Distributor distributor) {
        return service.save(distributor);
    }

    @PutMapping("/{id}")
    public Distributor update(@PathVariable Long id, @Valid @RequestBody Distributor distributor) {
        Distributor existing = service.findById(id);
        existing.setName(distributor.getName());
        existing.setInn(distributor.getInn());
        existing.setAddress(distributor.getAddress());
        existing.setLastName(distributor.getLastName());
        existing.setFirstName(distributor.getFirstName());
        existing.setMiddleName(distributor.getMiddleName());
        existing.setPhone(distributor.getPhone());
        existing.setEmail(distributor.getEmail());
        existing.setWebsite(distributor.getWebsite());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
