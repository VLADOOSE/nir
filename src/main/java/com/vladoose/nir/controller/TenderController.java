package com.vladoose.nir.controller;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{id}")
    public Tender findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public Tender create(@RequestBody Tender tender) {
        return service.save(tender);
    }

    @PutMapping("/{id}")
    public Tender update(@PathVariable Long id, @RequestBody Tender tender) {
        Tender existing = service.findById(id);
        existing.setTenderNumber(tender.getTenderNumber());
        existing.setFacility(tender.getFacility());
        existing.setStatus(tender.getStatus());
        existing.setDeadline(tender.getDeadline());
        existing.setTotalCost(tender.getTotalCost());
        existing.setDescription(tender.getDescription());
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
