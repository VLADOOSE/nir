package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.EquipmentTypeRequest;
import com.vladoose.nir.dto.response.EquipmentTypeResponse;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.mapper.EquipmentTypeMapper;
import com.vladoose.nir.service.EquipmentTypeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment-types")
public class EquipmentTypeController {

    private final EquipmentTypeService service;
    private final EquipmentTypeMapper mapper;

    public EquipmentTypeController(EquipmentTypeService service, EquipmentTypeMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<EquipmentTypeResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public EquipmentTypeResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EquipmentTypeResponse create(@Valid @RequestBody EquipmentTypeRequest request) {
        return mapper.toResponse(service.save(mapper.toEntity(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EquipmentTypeResponse update(@PathVariable Long id, @Valid @RequestBody EquipmentTypeRequest request) {
        EquipmentType existing = service.findById(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
