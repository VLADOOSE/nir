package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PriceRequestRequest;
import com.vladoose.nir.dto.request.PriceRequestSendRequest;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.dto.response.PriceRequestResponse;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.SupplierReplyFile;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.mapper.PriceRequestMapper;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.PriceRequestSendService;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestService;
import com.vladoose.nir.service.RfqDocumentService;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/price-requests")
public class PriceRequestController {

    private final PriceRequestService service;
    private final TenderService tenderService;
    private final TenderLotService tenderLotService;
    private final MedEquipmentService medEquipmentService;
    private final DistributorService distributorService;
    private final PriceRequestItemRepository itemRepository;
    private final PriceRequestMapper mapper;
    private final PriceRequestSendService sendService;
    private final SupplierReplyFileRepository fileRepository;

    public PriceRequestController(PriceRequestService service,
                                  TenderService tenderService,
                                  TenderLotService tenderLotService,
                                  MedEquipmentService medEquipmentService,
                                  DistributorService distributorService,
                                  PriceRequestItemRepository itemRepository,
                                  PriceRequestMapper mapper,
                                  PriceRequestSendService sendService,
                                  SupplierReplyFileRepository fileRepository) {
        this.service = service;
        this.tenderService = tenderService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.itemRepository = itemRepository;
        this.mapper = mapper;
        this.sendService = sendService;
        this.fileRepository = fileRepository;
    }

    /** Единый канал: создать КП на каждого поставщика и отправить письмо с токеном [КП-id]. */
    @PostMapping("/send")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PriceRequestSendService.SendResult> send(@Valid @RequestBody PriceRequestSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(
                        i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        return sendService.send(req.getTenderId(), req.getDistributorIds(), items,
                req.getSubjectOverride(), req.getBodyOverride(), options(req));
    }

    /** Опции панели КП тендера; клиента в тендерах не раскрываем (прежнее решение против утечки, спека §6.6). */
    private static SendOptions options(PriceRequestSendRequest req) {
        return new SendOptions(req.getReplyBy(), Boolean.TRUE.equals(req.getWithDocument()),
                Boolean.TRUE.equals(req.getWithStamp()), false, null);
    }

    /** Черновой текст письма КП (образец) — без сохранения PriceRequest и без токена в теме. */
    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public KpPreviewResponse preview(@Valid @RequestBody PriceRequestSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(
                        i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        return sendService.preview(req.getTenderId(), req.getDistributorIds(), items, options(req));
    }

    /** Переслать письмо существующего КП (повторно / после правки email поставщика). Новый PR не создаётся. */
    @PostMapping("/{id}/resend")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceRequestSendService.SendResult resend(@PathVariable Long id) {
        return sendService.resend(id);
    }

    /** Документ запроса — PDF (переслать в WhatsApp) или Word, с номером запроса; читать может любой вошедший. */
    @GetMapping("/{id}/document")
    public ResponseEntity<byte[]> document(@PathVariable Long id, @RequestParam(required = false) String format) {
        return PrivateRequestController.download(sendService.document(id, RfqDocumentService.Format.parse(format)));
    }

    @GetMapping
    public List<PriceRequestResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public PriceRequestResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @GetMapping("/by-tender/{tenderId}")
    public List<PriceRequestResponse> findByTender(@PathVariable Long tenderId) {
        return mapper.toResponseList(service.findByTenderId(tenderId));
    }

    @GetMapping("/by-distributor/{distributorId}")
    public List<PriceRequestResponse> findByDistributor(@PathVariable Long distributorId) {
        return mapper.toResponseList(service.findByDistributorId(distributorId));
    }

    @PostMapping
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PriceRequestResponse create(@Valid @RequestBody PriceRequestRequest request) {
        if (request.getTenderId() == null) throw new BadRequestException("Не указан тендер");
        if (request.getDistributorId() == null) throw new BadRequestException("Не указан дистрибьютор");

        PriceRequest entity = mapper.toEntity(request);
        entity.setTender(tenderService.findById(request.getTenderId()));
        entity.setDistributor(distributorService.findById(request.getDistributorId()));
        if (entity.getStatus() == null) entity.setStatus("CREATED");
        PriceRequest saved = service.save(entity);

        if (request.getItems() != null) {
            for (var itemReq : request.getItems()) {
                if (itemReq.getTenderLotId() == null) continue;
                PriceRequestItem item = PriceRequestItem.builder()
                        .priceRequest(saved)
                        .tenderLot(tenderLotService.findById(itemReq.getTenderLotId()))
                        .requestedQuantity(itemReq.getRequestedQuantity())
                        .responsePrice(itemReq.getResponsePrice())
                        .responseNote(itemReq.getResponseNote())
                        .build();
                if (itemReq.getMedEquipmentId() != null) {
                    item.setMedEquipment(medEquipmentService.findById(itemReq.getMedEquipmentId()));
                }
                PriceRequestItem persisted = itemRepository.save(item);
                saved.getItems().add(persisted);
            }
        }

        return mapper.toResponse(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PriceRequestResponse update(@PathVariable Long id, @Valid @RequestBody PriceRequestRequest request) {
        PriceRequest existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getTenderId() != null) existing.setTender(tenderService.findById(request.getTenderId()));
        if (request.getDistributorId() != null) existing.setDistributor(distributorService.findById(request.getDistributorId()));
        return mapper.toResponse(service.save(existing));
    }

    @PutMapping("/{id}/responses")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PriceRequestResponse updateResponses(@PathVariable Long id, @RequestBody List<ItemResponseDto> updates) {
        PriceRequest pr = service.findById(id);
        // findById = em.find — фильтр рынка его не режет (§14): запрос другого рынка — как несуществующий
        if (pr.getMarket() != null && pr.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Запрос КП не найден: " + id);
        }
        boolean anyPriceSet = false;
        for (ItemResponseDto u : updates) {
            if (u.itemId() == null) {
                throw new BadRequestException("itemId обязателен в каждой позиции");
            }
            if (u.responsePrice() != null && u.responsePrice().signum() < 0) {
                throw new BadRequestException("Цена ответа КП не может быть отрицательной (позиция " + u.itemId() + ")");
            }
            PriceRequestItem item = itemRepository.findById(u.itemId())
                    .orElseThrow(() -> new BadRequestException("Позиция КП не найдена: " + u.itemId()));
            if (item.getPriceRequest() == null || !item.getPriceRequest().getId().equals(id)) {
                throw new BadRequestException("Позиция " + u.itemId() + " не из этого запроса КП");
            }
            item.setResponsePrice(u.responsePrice());
            item.setResponseNote(u.responseNote());
            item.setResponseAuto(false);                    // ручное сохранение = сверено (спека §3.4)
            if (Boolean.TRUE.equals(u.clearFile())) {
                item.setResponseFileId(null);
                item.setResponseFileRow(null);
            } else if (u.fileId() != null && u.fileRow() != null) {
                if (u.fileRow() < 1) throw new BadRequestException("Номер строки файла — с 1 (позиция " + u.itemId() + ")");
                SupplierReplyFile f = fileRepository.findById(u.fileId())
                        .filter(x -> id.equals(x.getPriceRequestId()))
                        .orElseThrow(() -> new BadRequestException("Файл " + u.fileId() + " не из этого запроса цен"));
                item.setResponseFileId(f.getId());
                item.setResponseFileRow(u.fileRow());
            }
            itemRepository.save(item);
            if (u.responsePrice() != null) anyPriceSet = true;
        }
        if (anyPriceSet && "SENT".equals(pr.getStatus())) {
            pr.setStatus("RESPONDED");
            pr.setResponseDate(LocalDate.now());
            service.save(pr);
        }
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping("/{id}/close")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PriceRequestResponse close(@PathVariable Long id) {
        PriceRequest pr = service.findById(id);
        pr.setStatus("CLOSED");
        return mapper.toResponse(service.save(pr));
    }

    @PostMapping("/{id}/accept")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PriceRequestResponse accept(@PathVariable Long id) {
        PriceRequest pr = service.findById(id);
        if (!"RESPONDED".equals(pr.getStatus())) {
            throw new BadRequestException("Принять можно только КП в статусе «Ответ получен»");
        }
        pr.setStatus("ACCEPTED");
        return mapper.toResponse(service.save(pr));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    /**
     * Ответ по строке: цена, примечание и, по желанию, строка файла ответа (fileId + fileRow — выбрать, clearFile —
     * снять). Тело вкладки, открытой до V30, полей файла не несёт — связь остаётся как была.
     */
    public record ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote,
                                  Long fileId, Integer fileRow, Boolean clearFile) {
        public ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote) {
            this(itemId, responsePrice, responseNote, null, null, null);
        }
    }
}
