package com.vladoose.nir.controller;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.ApplyItemService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applies")
public class ActivityApplyController {

    private final ActivityApplyService service;
    private final ApplyItemService applyItemService;

    public ActivityApplyController(ActivityApplyService service, ApplyItemService applyItemService) {
        this.service = service;
        this.applyItemService = applyItemService;
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
    public ActivityApply create(@RequestBody ActivityApply apply) {
        return service.save(apply);
    }

    @PutMapping("/{id}")
    public ActivityApply update(@PathVariable Long id, @RequestBody ActivityApply apply) {
        ActivityApply existing = service.findById(id);
        existing.setTender(apply.getTender());
        existing.setStatus(apply.getStatus());
        existing.setCreatedAt(apply.getCreatedAt());
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
}
