package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.MatchRequest;
import com.vladoose.nir.dto.request.MedEquipmentRequest;
import com.vladoose.nir.dto.response.EquipmentMatchResponse;
import com.vladoose.nir.dto.response.EquipmentStatsResponse;
import com.vladoose.nir.dto.response.MedEquipmentResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.mapper.MedEquipmentMapper;
import com.vladoose.nir.service.EquipmentScoringService;
import com.vladoose.nir.service.EquipmentStatsService;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.TenderLotService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment")
public class MedEquipmentController {

    private final MedEquipmentService service;
    private final TenderLotService tenderLotService;
    private final MedEquipmentMapper mapper;
    private final EquipmentStatsService statsService;
    private final EquipmentScoringService scoringService;

    public MedEquipmentController(MedEquipmentService service,
                                  TenderLotService tenderLotService,
                                  MedEquipmentMapper mapper,
                                  EquipmentStatsService statsService,
                                  EquipmentScoringService scoringService) {
        this.service = service;
        this.tenderLotService = tenderLotService;
        this.mapper = mapper;
        this.statsService = statsService;
        this.scoringService = scoringService;
    }

    @GetMapping
    public List<MedEquipmentResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public MedEquipmentResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public MedEquipmentResponse create(@Valid @RequestBody MedEquipmentRequest request) {
        MedEquipment entity = mapper.toEntity(request);
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MedEquipmentResponse update(@PathVariable Long id, @Valid @RequestBody MedEquipmentRequest request) {
        MedEquipment existing = service.findById(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    @PostMapping("/match/{lotId}")
    public EquipmentMatchResponse smartMatch(@PathVariable Long lotId,
                                              @RequestBody(required = false) MatchRequest request) {
        MatchRequest req = request != null ? request : new MatchRequest();
        double[] weights = req.resolveWeights();
        return scoringService.scoreLot(lotId, weights, req.getPreset().name());
    }

    @GetMapping("/match/{lotId}")
    public EquipmentMatchResponse smartMatchDefault(@PathVariable Long lotId) {
        return scoringService.scoreLot(lotId, new MatchRequest().resolveWeights(), "BALANCED");
    }

    @GetMapping("/{id}/stats")
    public EquipmentStatsResponse stats(@PathVariable Long id) {
        return statsService.buildStats(id);
    }
}
