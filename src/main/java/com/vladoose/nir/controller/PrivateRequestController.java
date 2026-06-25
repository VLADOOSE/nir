package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ImportCommitRequest;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.PrivateRequestResponse;
import com.vladoose.nir.dto.response.SourcingPreviewResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.mapper.FacilityMapper;
import com.vladoose.nir.service.PrivateRequestImportService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/private-requests")
public class PrivateRequestController {

    private final PrivateRequestService service;
    private final FacilityMapper facilityMapper;
    private final PrivateRequestSourcingService sourcingService;
    private final PrivateRequestImportService importService;

    public PrivateRequestController(PrivateRequestService service, FacilityMapper facilityMapper,
                                    PrivateRequestSourcingService sourcingService,
                                    PrivateRequestImportService importService) {
        this.service = service;
        this.facilityMapper = facilityMapper;
        this.sourcingService = sourcingService;
        this.importService = importService;
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
        return buildFull(service.createFromLines(request));
    }

    @PostMapping("/import/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse importPreview(@RequestParam("file") MultipartFile file) throws IOException {
        return importService.preview(file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping("/import/commit")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse importCommit(@Valid @RequestBody ImportCommitRequest request) {
        return buildFull(importService.commit(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badFile(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    @GetMapping("/{id}/sourcing")
    public SourcingPreviewResponse sourcing(@PathVariable Long id) {
        return sourcingService.buildSourcing(id);
    }

    private PrivateRequestResponse buildFull(Tender t) {
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
