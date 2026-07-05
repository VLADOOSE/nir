package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ReconciliationRowResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.dto.response.RegistryDetailResponse;
import com.vladoose.nir.service.RegistryDetailService;
import com.vladoose.nir.service.RegistryImportService;
import com.vladoose.nir.service.RegistryMatchService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registry")
public class RegistryController {

    private final RegistryMatchService matchService;
    private final RegistryImportService importService;
    private final RegistryDetailService detailService;

    public RegistryController(RegistryMatchService matchService, RegistryImportService importService,
                              RegistryDetailService detailService) {
        this.matchService = matchService;
        this.importService = importService;
        this.detailService = detailService;
    }

    @GetMapping("/candidates")
    public List<RegistryCandidateResponse> candidates(@RequestParam(required = false) String name,
                                                      @RequestParam(required = false) String manufact,
                                                      @RequestParam(defaultValue = "5") int limit) {
        return matchService.findCandidates(name, manufact, limit);
    }

    @GetMapping("/candidates/equipment/{id}")
    public List<RegistryCandidateResponse> candidatesForEquipment(@PathVariable Long id,
                                                                  @RequestParam(defaultValue = "5") int limit) {
        return matchService.candidatesForEquipment(id, limit);
    }

    @GetMapping("/reconciliation")
    public List<ReconciliationRowResponse> reconciliation(@RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "5") int candidates) {
        return matchService.buildReconciliation(status, candidates);
    }

    @GetMapping("/search")
    public List<RegistryCandidateResponse> search(@RequestParam String q,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return matchService.findCandidates(q, q, limit);
    }

    /** Описание изделия из карточки НЦЭЛС (live при первом просмотре + кеш). Чтение — без ADMIN. */
    @GetMapping("/detail")
    public RegistryDetailResponse detail(@RequestParam String regNumber) {
        return detailService.detail(regNumber);
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> refresh() {
        int imported = importService.importFromDump();
        return Map.of("imported", imported);
    }
}
