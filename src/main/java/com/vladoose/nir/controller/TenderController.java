package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.AiAnswerRequest;
import com.vladoose.nir.dto.request.AiBriefRequest;
import com.vladoose.nir.dto.request.TenderRequest;
import com.vladoose.nir.dto.response.ActivityApplyResponse;
import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.dto.response.LotProductMatchDto;
import com.vladoose.nir.dto.response.OurProductSummary;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.dto.response.TenderResponse;
import com.vladoose.nir.entity.AiVerdict;
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
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.aianswer.AiAnswerService;
import com.vladoose.nir.service.productwatch.ProductAiBrief;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.lotmodel.LotModelViews;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.fit.SpecRequirement;
import com.vladoose.nir.service.productwatch.fit.SpecRequirements;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ProductAiBrief aiBrief;
    private final AiAnswerService aiAnswers;
    private final TenderLotRepository tenderLotRepository;
    private final LotModelViews lotModelViews;

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
                            ProductMatchQuery productQuery,
                            ProductAiBrief aiBrief,
                            TenderLotRepository tenderLotRepository,
                            LotModelViews lotModelViews,
                            AiAnswerService aiAnswers) {
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
        this.aiBrief = aiBrief;
        this.tenderLotRepository = tenderLotRepository;
        this.lotModelViews = lotModelViews;
        this.aiAnswers = aiAnswers;
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
        Map<Long, List<String>> models = new HashMap<>();
        for (Object[] row : tenderLotRepository.findModelsByTenderIds(list.stream().map(TenderResponse::getId).toList())) {
            models.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        for (TenderResponse r : list) r.setModelsInWork(models.getOrDefault(r.getId(), List.of()));
        Set<Long> analog = new HashSet<>(tenderLotRepository.findTenderIdsByAiVerdict(list.stream().map(TenderResponse::getId).toList(),
                AiVerdict.ANALOG));
        for (TenderResponse r : list) r.setAiAnalog(analog.contains(r.getId()));
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
        List<TenderLot> entities = tenderLotService.findByTenderId(id);
        List<TenderLotResponse> lots = tenderLotMapper.toResponseList(entities);
        Map<Long, TenderLot> byId = new HashMap<>();
        entities.forEach(e -> byId.put(e.getId(), e));
        for (TenderLotResponse l : lots) lotModelViews.enrich(l, byId.get(l.getId()));   // ссылка на товар сайта, РУ модели
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

    /** «📋 Для ИИ» по одному тендеру (спека 2026-10-20-product-spec-fit §9): текст для вставки в Claude / Gemini. */
    @GetMapping(value = "/{id}/ai-brief", produces = "text/plain;charset=UTF-8")
    public String aiBrief(@PathVariable Long id) {
        return aiBrief.forTender(id);
    }

    @PostMapping("/ai-brief")
    public AiBriefResponse aiBriefList(@RequestBody AiBriefRequest req) {
        return aiBrief.forTenders(req.ids());
    }

    /** «✍️ Вставить ответ ИИ» (спека 2026-10-21-tender-telegram-bot §6, §10 п. 3): разбор блока, модели, письма. */
    @PostMapping("/ai-answer")
    @PreAuthorize("hasRole('ADMIN')")
    public AiAnswerResult aiAnswer(@Valid @RequestBody AiAnswerRequest req) {
        return aiAnswers.apply(req.getText());
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
