package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ProposedEquipmentRequest;
import com.vladoose.nir.dto.request.TenderLotRequest;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.mapper.TenderLotMapper;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lots")
public class TenderLotController {

    private final TenderLotService service;
    private final TenderService tenderService;
    private final TenderLotMapper mapper;
    private final com.vladoose.nir.service.RegistryMatchService registryMatchService;
    private final MedEquipmentService medEquipmentService;

    public TenderLotController(TenderLotService service,
                               TenderService tenderService,
                               TenderLotMapper mapper,
                               com.vladoose.nir.service.RegistryMatchService registryMatchService,
                               MedEquipmentService medEquipmentService) {
        this.service = service;
        this.tenderService = tenderService;
        this.mapper = mapper;
        this.registryMatchService = registryMatchService;
        this.medEquipmentService = medEquipmentService;
    }

    /** Утвердить модель каталога как «предложенное оборудование» лота. */
    @PostMapping("/{id}/proposed-equipment")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse setProposedEquipment(@PathVariable Long id,
                                                  @Valid @RequestBody ProposedEquipmentRequest request) {
        TenderLot lot = service.findById(id);
        // em.find обходит hibernate-фильтр рынка — явные гарды от чужого рынка и для лота (через тендер), и для оборудования
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + id);
        }
        MedEquipment eq = medEquipmentService.findById(request.getEquipmentId());
        if (eq.getMarket() != null && eq.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Оборудование не найдено: id=" + request.getEquipmentId());
        }
        lot.setProposedEquipment(eq);
        return mapper.toResponse(service.save(lot));
    }

    @DeleteMapping("/{id}/proposed-equipment")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse clearProposedEquipment(@PathVariable Long id) {
        TenderLot lot = service.findById(id);
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + id);
        }
        lot.setProposedEquipment(null);
        return mapper.toResponse(service.save(lot));
    }

    @GetMapping("/{id}")
    public TenderLotResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    /** Кандидаты реестра НЦЭЛС по лоту: «что это за изделие» (топ по похожести названия). */
    @GetMapping("/{id}/registry-candidates")
    public java.util.List<com.vladoose.nir.dto.response.RegistryCandidateResponse> registryCandidates(
            @PathVariable Long id, @RequestParam(defaultValue = "5") int limit) {
        return registryMatchService.candidatesForLot(id, Math.min(limit, 20));
    }

    @PostMapping
    public TenderLotResponse create(@Valid @RequestBody TenderLotRequest request) {
        if (request.getTenderId() == null) {
            throw new BadRequestException("Не указан тендер");
        }
        TenderLot lot = mapper.toEntity(request);
        // Replace the stub Tender reference (id only) with a managed entity so JPA can use it
        Tender tender = tenderService.findById(request.getTenderId());
        lot.setTender(tender);
        return mapper.toResponse(service.save(lot));
    }

    @PutMapping("/{id}")
    public TenderLotResponse update(@PathVariable Long id, @Valid @RequestBody TenderLotRequest request) {
        TenderLot existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getTenderId() != null) {
            // Replace the stub reference with the managed entity
            Tender tender = tenderService.findById(request.getTenderId());
            existing.setTender(tender);
        }
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
