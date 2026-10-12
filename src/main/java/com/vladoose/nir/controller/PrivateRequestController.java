package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.PrivateRequestImportService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.util.PastedLinesParser;
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
    private final PrivateRequestSourcingService sourcingService;
    private final PrivateRequestImportService importService;

    public PrivateRequestController(PrivateRequestService service, PrivateRequestViewBuilder viewBuilder,
                                    PrivateRequestSourcingService sourcingService, PrivateRequestImportService importService) {
        this.service = service;
        this.viewBuilder = viewBuilder;
        this.sourcingService = sourcingService;
        this.importService = importService;
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

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse addLines(@PathVariable Long id, @Valid @RequestBody PrivateRequestLinesAdd req) {
        return viewBuilder.build(service.addLines(id, req.getLines(), req.getMode() == null ? LinesMode.APPEND : req.getMode()));
    }

    @PatchMapping("/{id}/lines/{lotId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestLineView updateLine(@PathVariable Long id, @PathVariable Long lotId,
                                             @Valid @RequestBody PrivateRequestLineFieldUpdate req) {
        TenderLot lot = service.updateLineField(id, lotId, req.getField(), req.getValue());
        return viewBuilder.line(lot);
    }

    @DeleteMapping("/{id}/lines/{lotId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deleteLine(@PathVariable Long id, @PathVariable Long lotId) {
        return viewBuilder.build(service.deleteLine(id, lotId));
    }

    @PostMapping("/{id}/lines/{lotId}/registry")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestLineView lineRegistry(@PathVariable Long id, @PathVariable Long lotId,
                                               @Valid @RequestBody LineRegistryActionRequest req) {
        return viewBuilder.line(service.lineRegistry(id, lotId, req.getAction(), req.getRegNumber()));
    }

    @PostMapping("/{id}/lines/parse")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ParsedLineResponse> parseLines(@PathVariable Long id, @Valid @RequestBody PastedLinesRequest req) {
        service.findById(id);   // гард: заявка своего рынка
        return PastedLinesParser.parse(req.getText()).stream()
                .map(p -> new ParsedLineResponse(p.name(), p.quantity(), p.unit()))
                .toList();
    }

    @GetMapping("/{id}/lines/{lotId}/registry-candidates")
    public List<RegistryCandidateResponse> lineCandidates(@PathVariable Long id, @PathVariable Long lotId) {
        return service.lineCandidates(id, lotId);
    }

    @PostMapping("/{id}/registry-check")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse registryCheck(@PathVariable Long id) {
        return viewBuilder.build(service.registryCheck(id));
    }

    @PostMapping("/import/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse importPreview(@RequestParam("file") MultipartFile file) throws IOException {
        return importService.preview(file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping("/import/commit")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse importCommit(@Valid @RequestBody ImportCommitRequest request) {
        return viewBuilder.build(importService.commit(request));
    }

    @PostMapping("/{id}/lines/import")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse importLines(@PathVariable Long id, @Valid @RequestBody ImportLinesRequest request) {
        return viewBuilder.build(importService.importInto(id, request));
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

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
