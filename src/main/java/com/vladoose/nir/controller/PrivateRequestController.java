package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.PrivateRequestResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.mapper.FacilityMapper;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/private-requests")
public class PrivateRequestController {

    private final PrivateRequestService service;
    private final FacilityMapper facilityMapper;

    public PrivateRequestController(PrivateRequestService service, FacilityMapper facilityMapper) {
        this.service = service;
        this.facilityMapper = facilityMapper;
    }

    @GetMapping
    public List<PrivateRequestResponse> findAll() {
        return service.findAll().stream().map(t -> {
            PrivateRequestResponse r = toShort(t);
            applyCounts(r, service.linesWithRegistration(t.getId()));
            return r;
        }).toList();
    }

    @GetMapping("/{id}")
    public PrivateRequestResponse findById(@PathVariable Long id) {
        Tender t = service.findById(id);
        PrivateRequestResponse r = toShort(t);
        List<PrivateRequestLineResponse> lines = service.linesWithRegistration(id);
        applyCounts(r, lines);
        r.setLines(lines);
        return r;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse create(@Valid @RequestBody PrivateRequestCreate request) {
        Tender t = service.createFromLines(request);
        PrivateRequestResponse r = toShort(t);
        List<PrivateRequestLineResponse> lines = service.linesWithRegistration(t.getId());
        applyCounts(r, lines);
        r.setLines(lines);
        return r;
    }

    private PrivateRequestResponse toShort(Tender t) {
        PrivateRequestResponse r = new PrivateRequestResponse();
        r.setId(t.getId());
        r.setNumber(t.getTenderNumber());
        r.setStatus(t.getStatus());
        if (t.getFacility() != null) {
            r.setClient(facilityMapper.toResponse(t.getFacility()));
        }
        return r;
    }

    private void applyCounts(PrivateRequestResponse r, List<PrivateRequestLineResponse> lines) {
        r.setLineCount(lines.size());
        r.setRegisteredCount((int) lines.stream()
                .filter(l -> "REGISTERED".equals(l.getRegistrationStatus()))
                .count());
    }
}
