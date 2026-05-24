package com.vladoose.nir.controller;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

    public TenderController(TenderService service,
                            TenderLotService tenderLotService,
                            ActivityApplyService activityApplyService) {
        this.service = service;
        this.tenderLotService = tenderLotService;
        this.activityApplyService = activityApplyService;
    }

    @GetMapping
    public List<Tender> findAll() {
        return service.findAll();
    }

    @GetMapping("/search")
    public List<Tender> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) String equipType,
            @RequestParam(required = false) BigDecimal minCost,
            @RequestParam(required = false) BigDecimal maxCost,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return service.searchTenders(status, facilityId, equipType, minCost, maxCost, dateFrom, dateTo);
    }

    @GetMapping("/{id}")
    public Tender findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public Tender create(@Valid @RequestBody Tender tender) {
        if (tender.getDeadline() != null && tender.getDeadline().isBefore(LocalDate.now())) {
            throw new BadRequestException("Дедлайн не может быть в прошлом");
        }
        return service.save(tender);
    }

    @PutMapping("/{id}")
    public Tender update(@PathVariable Long id, @Valid @RequestBody Tender tender) {
        if (tender.getDeadline() != null && tender.getDeadline().isBefore(LocalDate.now())) {
            throw new BadRequestException("Дедлайн не может быть в прошлом");
        }
        Tender existing = service.findById(id);
        existing.setTenderNumber(tender.getTenderNumber());
        existing.setFacility(tender.getFacility());
        existing.setStatus(tender.getStatus());
        existing.setPurchaseType(tender.getPurchaseType());
        existing.setDeadline(tender.getDeadline());
        existing.setPublishDate(tender.getPublishDate());
        existing.setTotalCost(tender.getTotalCost());
        existing.setCurrency(tender.getCurrency());
        existing.setDescription(tender.getDescription());
        existing.setDeliveryAddress(tender.getDeliveryAddress());
        existing.setContactLastName(tender.getContactLastName());
        existing.setContactFirstName(tender.getContactFirstName());
        existing.setContactMiddleName(tender.getContactMiddleName());
        existing.setContactPhone(tender.getContactPhone());
        existing.setContactEmail(tender.getContactEmail());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    @GetMapping("/{id}/lots")
    public List<TenderLot> getLots(@PathVariable Long id) {
        return tenderLotService.findByTenderId(id);
    }

    @GetMapping("/{id}/applies")
    public List<ActivityApply> getApplies(@PathVariable Long id) {
        return activityApplyService.findByTenderId(id);
    }
}
