package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.PriceRequestRequest;
import com.vladoose.nir.dto.response.PriceRequestResponse;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.PriceRequestMapper;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.PriceRequestService;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
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

    public PriceRequestController(PriceRequestService service,
                                  TenderService tenderService,
                                  TenderLotService tenderLotService,
                                  MedEquipmentService medEquipmentService,
                                  DistributorService distributorService,
                                  PriceRequestItemRepository itemRepository,
                                  PriceRequestMapper mapper) {
        this.service = service;
        this.tenderService = tenderService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.itemRepository = itemRepository;
        this.mapper = mapper;
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
    public PriceRequestResponse update(@PathVariable Long id, @Valid @RequestBody PriceRequestRequest request) {
        PriceRequest existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getTenderId() != null) existing.setTender(tenderService.findById(request.getTenderId()));
        if (request.getDistributorId() != null) existing.setDistributor(distributorService.findById(request.getDistributorId()));
        return mapper.toResponse(service.save(existing));
    }

    @PutMapping("/{id}/responses")
    @Transactional
    public PriceRequestResponse updateResponses(@PathVariable Long id, @RequestBody List<ItemResponseDto> updates) {
        PriceRequest pr = service.findById(id);
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
            item.setResponsePrice(u.responsePrice());
            item.setResponseNote(u.responseNote());
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
    public PriceRequestResponse close(@PathVariable Long id) {
        PriceRequest pr = service.findById(id);
        pr.setStatus("CLOSED");
        return mapper.toResponse(service.save(pr));
    }

    @PostMapping("/{id}/accept")
    @Transactional
    public PriceRequestResponse accept(@PathVariable Long id) {
        PriceRequest pr = service.findById(id);
        if (!"RESPONDED".equals(pr.getStatus())) {
            throw new BadRequestException("Принять можно только КП в статусе «Ответ получен»");
        }
        pr.setStatus("ACCEPTED");
        return mapper.toResponse(service.save(pr));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    public record ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote) {}
}
