package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.FacilityRequest;
import com.vladoose.nir.dto.response.FacilityResponse;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.mapper.FacilityMapper;
import com.vladoose.nir.service.FacilityService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService service;
    private final FacilityMapper mapper;

    public FacilityController(FacilityService service, FacilityMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<FacilityResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public FacilityResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public FacilityResponse create(@Valid @RequestBody FacilityRequest request) {
        Facility entity = mapper.toEntity(request);
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public FacilityResponse update(@PathVariable Long id, @Valid @RequestBody FacilityRequest request) {
        Facility existing = service.findById(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
