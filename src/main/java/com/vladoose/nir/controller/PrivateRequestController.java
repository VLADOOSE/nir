package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.mapper.FacilityMapper;
import com.vladoose.nir.mapper.LeadResponseMapper;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.PrivateRequestImportService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/private-requests")
public class PrivateRequestController {

    private final PrivateRequestService service;
    private final PrivateRequestViewBuilder viewBuilder;
    private final FacilityMapper facilityMapper;
    private final PrivateRequestSourcingService sourcingService;
    private final PrivateRequestImportService importService;
    private final LeadService leadService;
    private final LeadResponseMapper leadResponseMapper;

    public PrivateRequestController(PrivateRequestService service, PrivateRequestViewBuilder viewBuilder,
                                    FacilityMapper facilityMapper, PrivateRequestSourcingService sourcingService,
                                    PrivateRequestImportService importService,
                                    LeadService leadService, LeadResponseMapper leadResponseMapper) {
        this.service = service;
        this.viewBuilder = viewBuilder;
        this.facilityMapper = facilityMapper;
        this.sourcingService = sourcingService;
        this.importService = importService;
        this.leadService = leadService;
        this.leadResponseMapper = leadResponseMapper;
    }

    @GetMapping
    public List<PrivateRequestListItemResponse> list(
            @RequestParam(defaultValue = "OPEN") PrivateRequestService.ListFilter status,
            @RequestParam(required = false) String q) {
        return viewBuilder.list(service.list(status, q));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse create() {
        return viewBuilder.build(service.createEmpty());
    }

    @GetMapping("/{id}")
    public PrivateRequestViewResponse get(@PathVariable Long id) {
        return viewBuilder.build(service.findById(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse updateField(@PathVariable Long id, @Valid @RequestBody PrivateRequestFieldUpdate req) {
        return viewBuilder.build(service.updateField(id, req.getField(), req.getValue()));
    }

    @PostMapping("/{id}/save-client")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse saveClient(@PathVariable Long id) {
        return viewBuilder.build(service.saveClient(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse close(@PathVariable Long id, @Valid @RequestBody PrivateRequestCloseRequest req) {
        return viewBuilder.build(service.close(id, req.getOutcome()));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse reopen(@PathVariable Long id) {
        return viewBuilder.build(service.reopen(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id, currentUser());
    }

    /** Старая правка строк целиком (задача 4 заменит её позициями по одной). */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse update(@PathVariable Long id, @Valid @RequestBody PrivateRequestUpdate request) {
        return buildFull(service.update(id, request));
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
        r.setLineCount(lines.size());
        r.setRegisteredCount((int) lines.stream().filter(l -> "REGISTERED".equals(l.getRegistrationStatus())).count());
        r.setLines(lines);
        leadService.findByPrivateRequest(t.getId()).map(leadResponseMapper::toRef).ifPresent(r::setLead);
        return r;
    }

    private PrivateRequestResponse toShort(Tender t) {
        PrivateRequestResponse r = new PrivateRequestResponse();
        r.setId(t.getId());
        r.setNumber(t.getTenderNumber());
        r.setStatus(t.getStatus());
        if (t.getFacility() != null) r.setClient(facilityMapper.toResponse(t.getFacility()));
        return r;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
