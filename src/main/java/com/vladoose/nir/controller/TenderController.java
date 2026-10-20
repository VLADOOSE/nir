package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.TenderRequest;
import com.vladoose.nir.dto.response.ActivityApplyResponse;
import com.vladoose.nir.dto.response.LotProductMatchDto;
import com.vladoose.nir.dto.response.OurProductSummary;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.dto.response.TenderResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.dto.response.OfferComparisonResponse;
import com.vladoose.nir.integration.goszakup.GoszakupImportScheduler;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyImportScheduler;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.LotSourcingService;
import com.vladoose.nir.service.OfferComparisonService;
import com.vladoose.nir.service.WinnerAssignmentService;
import com.vladoose.nir.service.TenderWorkStageService;
import com.vladoose.nir.dto.request.AssignWinnerRequest;
import com.vladoose.nir.dto.response.AssignWinnerResponse;
import com.vladoose.nir.mapper.ActivityApplyMapper;
import com.vladoose.nir.mapper.TenderLotMapper;
import com.vladoose.nir.mapper.TenderMapper;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.fit.SpecRequirement;
import com.vladoose.nir.service.productwatch.fit.SpecRequirements;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final SkPharmacyImportScheduler skScheduler;
    private final LotSourcingService lotSourcingService;
    private final OfferComparisonService offerComparisonService;
    private final WinnerAssignmentService winnerAssignmentService;
    private final TenderWorkStageService workStageService;
    private final ProductMatchQuery productQuery;

    public TenderController(TenderService service,
                            TenderLotService tenderLotService,
                            ActivityApplyService activityApplyService,
                            TenderMapper mapper,
                            TenderLotMapper tenderLotMapper,
                            ActivityApplyMapper activityApplyMapper,
                            GoszakupImportScheduler goszakupScheduler,
                            LotSourcingService lotSourcingService,
                            OfferComparisonService offerComparisonService,
                            WinnerAssignmentService winnerAssignmentService,
                            TenderWorkStageService workStageService,
                            SkPharmacyImportScheduler skScheduler,
                            ProductMatchQuery productQuery) {
        this.service = service;
        this.tenderLotService = tenderLotService;
        this.activityApplyService = activityApplyService;
        this.mapper = mapper;
        this.tenderLotMapper = tenderLotMapper;
        this.activityApplyMapper = activityApplyMapper;
        this.goszakupScheduler = goszakupScheduler;
        this.lotSourcingService = lotSourcingService;
        this.offerComparisonService = offerComparisonService;
        this.winnerAssignmentService = winnerAssignmentService;
        this.workStageService = workStageService;
        this.skScheduler = skScheduler;
        this.productQuery = productQuery;
    }

    /** Подсказки поставщиков для запроса КП по выбранным лотам. */
    @GetMapping("/{id}/lot-sourcing")
    public LotSourcingResponse lotSourcing(@PathVariable Long id, @RequestParam List<Long> lotIds,
                                           @RequestParam(required = false) String term) {
        return lotSourcingService.build(id, lotIds, term);
    }

    /** Сводка предложений поставщиков по тендеру (матрица лоты×поставщики, мин. цена по лоту). */
    @GetMapping("/{id}/offer-comparison")
    public OfferComparisonResponse offerComparison(@PathVariable Long id) {
        return offerComparisonService.build(id);
    }

    /** Стадия воронки по каждому тендеру рынка (tenderId → стадия). Только затронутые тендеры. */
    @GetMapping("/work-stages")
    public Map<Long, String> workStages() {
        return workStageService.stagesForMarket().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().name()));
    }

    /** Ручное назначение победителя по лоту (выбранный поставщик из сравнения → позиция заявки). */
    @PostMapping("/{id}/assign-winner")
    @PreAuthorize("hasRole('ADMIN')")
    public AssignWinnerResponse assignWinner(@PathVariable Long id, @Valid @RequestBody AssignWinnerRequest req) {
        return winnerAssignmentService.assignWinner(id, req.getLotId(), req.getPriceRequestId(), req.getMarkupPercent());
    }

    @GetMapping
    public List<TenderResponse> findAll() {
        return withOurProduct(mapper.toResponseList(service.findAll()));
    }

    /** Сводки «наш товар» (спека 2026-10-19-product-watch §12.2) — одним заходом на весь список, без N+1. */
    private List<TenderResponse> withOurProduct(List<TenderResponse> list) {
        if (list.isEmpty()) return list;
        Map<Long, OurProductSummary> ours = productQuery.ourProduct(list.stream().map(TenderResponse::getId).toList());
        for (TenderResponse r : list) r.setOurProduct(ours.get(r.getId()));
        return list;
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
        return withOurProduct(mapper.toResponseList(
                service.searchTenders(status, facilityId, equipType, minCost, maxCost, dateFrom, dateTo)));
    }

    /** Карточка тендера: чужой рынок и частная заявка — 404 (TenderService.findPublic). */
    @GetMapping("/{id}")
    public TenderResponse findById(@PathVariable Long id) {
        return withOurProduct(List.of(mapper.toResponse(service.findPublic(id)))).get(0);
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
        Tender existing = service.findPublic(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.findPublic(id);
        service.deleteById(id);
    }

    /** Лоты тендера: у лота своего фильтра рынка нет («через тендер») — сверка рынка по самому тендеру. */
    @GetMapping("/{id}/lots")
    public List<TenderLotResponse> getLots(@PathVariable Long id) {
        service.findInMarket(id);
        List<TenderLotResponse> lots = tenderLotMapper.toResponseList(tenderLotService.findByTenderId(id));
        Map<Long, List<LotProductMatchDto>> ours = productQuery.lotMatches(id);
        for (TenderLotResponse l : lots) l.setProductMatches(ours.getOrDefault(l.getId(), List.of()));
        for (TenderLotResponse l : lots) {
            if (l.getTechSpecStatus() == TechSpecStatus.OK && l.getRequiredSpec() != null) {
                l.setRequirements(SpecRequirements.extract(ProductMatchService.fitText(l.getRequiredSpec(), null)).stream()
                        .map(SpecRequirement::text).toList());
            }
        }
        return lots;
    }

    @GetMapping("/{id}/applies")
    public List<ActivityApplyResponse> getApplies(@PathVariable Long id) {
        return activityApplyMapper.toResponseList(activityApplyService.findByTenderId(id));
    }

    @PostMapping("/import-kz")
    @PreAuthorize("hasRole('ADMIN')")
    public GoszakupImportScheduler.ImportStatus importKz(@RequestParam(required = false) String region) {
        // импорт идёт в фоне (рынок KZ ставится в фоновом потоке, §6);
        // region фильтрует реестр учреждений (мониторимые больницы), НЕ серверный КАТО-фильтр goszakup; прогресс — GET /import-kz/status
        return goszakupScheduler.startAsync(region);
    }

    @GetMapping("/import-kz/status")
    public GoszakupImportScheduler.ImportStatus importKzStatus() {
        return goszakupScheduler.status();
    }

    /** Импорт тендеров СК-Фармации (fms.ecc.kz, HTML-скрейп) в фоне; прогресс — GET /import-sk/status. */
    @PostMapping("/import-sk")
    @PreAuthorize("hasRole('ADMIN')")
    public SkPharmacyImportScheduler.ImportStatus importSk() {
        return skScheduler.startAsync();
    }

    @GetMapping("/import-sk/status")
    public SkPharmacyImportScheduler.ImportStatus importSkStatus() {
        return skScheduler.status();
    }
}
