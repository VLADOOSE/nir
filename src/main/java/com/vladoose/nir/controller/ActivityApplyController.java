package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ActivityApplyRequest;
import com.vladoose.nir.dto.response.ActivityApplyResponse;
import com.vladoose.nir.dto.response.ApplyItemResponse;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.mapper.ActivityApplyMapper;
import com.vladoose.nir.mapper.ApplyItemMapper;
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
    private final ActivityApplyMapper mapper;
    private final ApplyItemMapper applyItemMapper;

    public ActivityApplyController(ActivityApplyService service,
                                   ApplyItemService applyItemService,
                                   JasperReportService jasperReportService,
                                   TenderService tenderService,
                                   ActivityApplyMapper mapper,
                                   ApplyItemMapper applyItemMapper) {
        this.service = service;
        this.applyItemService = applyItemService;
        this.jasperReportService = jasperReportService;
        this.tenderService = tenderService;
        this.mapper = mapper;
        this.applyItemMapper = applyItemMapper;
    }

    @GetMapping
    public List<ActivityApplyResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public ActivityApplyResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    public ActivityApplyResponse create(@Valid @RequestBody ActivityApplyRequest request) {
        ActivityApply entity = mapper.toEntity(request);
        if (request.getTenderId() != null) {
            entity.setTender(tenderService.findById(request.getTenderId()));
        }
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    public ActivityApplyResponse update(@PathVariable Long id, @Valid @RequestBody ActivityApplyRequest request) {
        ActivityApply existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getTenderId() != null) {
            existing.setTender(tenderService.findById(request.getTenderId()));
        }
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    @GetMapping("/{id}/items")
    public List<ApplyItemResponse> getItems(@PathVariable Long id) {
        return applyItemMapper.toResponseList(applyItemService.findByApplyId(id));
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
