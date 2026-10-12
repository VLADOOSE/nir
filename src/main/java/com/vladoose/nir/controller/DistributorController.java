package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.DistributorRequest;
import com.vladoose.nir.dto.response.DistributorResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.util.EmailAddresses;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/distributors")
public class DistributorController {

    private final DistributorService service;
    private final DistributorMapper mapper;

    public DistributorController(DistributorService service, DistributorMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<DistributorResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public DistributorResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public DistributorResponse create(@Valid @RequestBody DistributorRequest request) {
        normalizeEmail(request);
        Distributor entity = mapper.toEntity(request);
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DistributorResponse update(@PathVariable Long id, @Valid @RequestBody DistributorRequest request) {
        normalizeEmail(request);
        Distributor existing = service.findById(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    /** Почта карточки — один или несколько адресов (спека 2026-10-12 §6.2); неверный — 400 с адресом. Не прислана — не трогаем. */
    private static void normalizeEmail(DistributorRequest r) {
        if (r.getEmail() != null) r.setEmail(EmailAddresses.normalize(r.getEmail()));
    }
}
