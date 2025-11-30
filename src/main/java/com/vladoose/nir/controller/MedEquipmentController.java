package com.vladoose.nir.controller;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.service.MedEquipmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/med-equipment")
public class MedEquipmentController {
    private final MedEquipmentService service;
    public MedEquipmentController(MedEquipmentService service) { this.service = service; }

    @GetMapping
    public List<MedEquipment> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public MedEquipment get(@PathVariable Long id) { return service.findById(id); }

    @PostMapping
    public ResponseEntity<MedEquipment> create(@RequestBody MedEquipment dto) {
        MedEquipment created = service.create(dto);
        return ResponseEntity.created(URI.create("/api/med-equipment/" + created.getMedEquipId())).body(created);
    }

    @PutMapping("/{id}")
    public MedEquipment update(@PathVariable Long id, @RequestBody MedEquipment dto) { return service.update(id, dto); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.delete(id); return ResponseEntity.noContent().build(); }
}