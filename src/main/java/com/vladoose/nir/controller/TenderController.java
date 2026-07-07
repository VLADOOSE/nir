package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.TenderRequest;
import com.vladoose.nir.dto.response.ActivityApplyResponse;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.dto.response.TenderResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.integration.goszakup.GoszakupImportScheduler;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.LotSourcingService;
import com.vladoose.nir.mapper.ActivityApplyMapper;
import com.vladoose.nir.mapper.TenderLotMapper;
import com.vladoose.nir.mapper.TenderMapper;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tenders")
public class TenderController {

    private final TenderService service;
    private final TenderLotService tenderLotService;
    private final ActivityApplyService activityApplyService;
    private final TenderMapper mapper;
    private final TenderLotMapper tenderLotMapper;
    private final ActivityApplyMapper activityApplyMapper;
    private final GoszakupImportScheduler goszakupScheduler;
    private final LotSourcingService lotSourcingService;

    public TenderController(TenderService service,
                            TenderLotService tenderLotService,
                            ActivityApplyService activityApplyService,
                            TenderMapper mapper,
                            TenderLotMapper tenderLotMapper,
                            ActivityApplyMapper activityApplyMapper,
                            GoszakupImportScheduler goszakupScheduler,
                            LotSourcingService lotSourcingService) {
        this.service = service;
        this.tenderLotService = tenderLotService;
        this.activityApplyService = activityApplyService;
        this.mapper = mapper;
        this.tenderLotMapper = tenderLotMapper;
        this.activityApplyMapper = activityApplyMapper;
        this.goszakupScheduler = goszakupScheduler;
        this.lotSourcingService = lotSourcingService;
    }

    /** Подсказки поставщиков для запроса КП по выбранным лотам. */
    @GetMapping("/{id}/lot-sourcing")
    public LotSourcingResponse lotSourcing(@PathVariable Long id, @RequestParam List<Long> lotIds,
                                           @RequestParam(required = false) String term) {
        return lotSourcingService.build(id, lotIds, term);
    }

    @GetMapping
    public List<TenderResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/search")
    public List<TenderResponse> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) String equipType,
            @RequestParam(required = false) BigDecimal minCost,
            @RequestParam(required = false) BigDecimal maxCost,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return mapper.toResponseList(
                service.searchTenders(status, facilityId, equipType, minCost, maxCost, dateFrom, dateTo));
    }

    @GetMapping("/{id}")
    public TenderResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    public TenderResponse create(@Valid @RequestBody TenderRequest request) {
        if (request.getDeadline() != null && request.getDeadline().isBefore(LocalDate.now())) {
            throw new BadRequestException("Дедлайн не может быть в прошлом");
        }
        Tender entity = mapper.toEntity(request);
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    public TenderResponse update(@PathVariable Long id, @Valid @RequestBody TenderRequest request) {
        if (request.getDeadline() != null && request.getDeadline().isBefore(LocalDate.now())) {
            throw new BadRequestException("Дедлайн не может быть в прошлом");
        }
        Tender existing = service.findById(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    @GetMapping("/{id}/lots")
    public List<TenderLotResponse> getLots(@PathVariable Long id) {
        return tenderLotMapper.toResponseList(tenderLotService.findByTenderId(id));
    }

    @GetMapping("/{id}/applies")
    public List<ActivityApplyResponse> getApplies(@PathVariable Long id) {
        return activityApplyMapper.toResponseList(activityApplyService.findByTenderId(id));
    }

    @PostMapping("/import-kz")
    @PreAuthorize("hasRole('ADMIN')")
    public GoszakupImportScheduler.ImportStatus importKz(@RequestParam(required = false) String region) {
        // импорт идёт в фоне (рынок KZ ставится в фоновом потоке, §6);
        // region — серверный КАТО-фильтр goszakup; прогресс — GET /import-kz/status
        return goszakupScheduler.startAsync(region);
    }

    @GetMapping("/import-kz/status")
    public GoszakupImportScheduler.ImportStatus importKzStatus() {
        return goszakupScheduler.status();
    }
}
