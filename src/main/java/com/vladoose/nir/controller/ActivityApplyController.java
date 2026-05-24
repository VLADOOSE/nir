package com.vladoose.nir.controller;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.ApplyItemService;
import com.vladoose.nir.service.JasperReportService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applies")
public class ActivityApplyController {

    private final ActivityApplyService service;
    private final ApplyItemService applyItemService;
    private final JasperReportService jasperReportService;
    private final TenderService tenderService;

    public ActivityApplyController(ActivityApplyService service,
                                   ApplyItemService applyItemService,
                                   JasperReportService jasperReportService,
                                   TenderService tenderService) {
        this.service = service;
        this.applyItemService = applyItemService;
        this.jasperReportService = jasperReportService;
        this.tenderService = tenderService;
    }

    @GetMapping
    public List<ActivityApply> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ActivityApply findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public ActivityApply create(@Valid @RequestBody ActivityApply apply) {
        if (apply.getTender() != null && apply.getTender().getId() != null) {
            apply.setTender(tenderService.findById(apply.getTender().getId()));
        }
        return service.save(apply);
    }

    @PutMapping("/{id}")
    public ActivityApply update(@PathVariable Long id, @Valid @RequestBody ActivityApply apply) {
        ActivityApply existing = service.findById(id);
        if (apply.getTender() != null && apply.getTender().getId() != null) {
            existing.setTender(tenderService.findById(apply.getTender().getId()));
        }
        existing.setStatus(apply.getStatus());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    @GetMapping("/{id}/items")
    public List<ApplyItem> getItems(@PathVariable Long id) {
        return applyItemService.findByApplyId(id);
    }

    @GetMapping(value = "/{id}/pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> applyPdf(@PathVariable Long id) {
        try {
            ActivityApply apply = service.findById(id);
            List<ApplyItem> items = applyItemService.findByApplyId(id);
            byte[] pdf = jasperReportService.generateApplyReport(apply, items);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=apply_" + id + ".pdf")
                    .header("Content-Type", "application/pdf")
                    .body(pdf);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
