package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PlanPointDto;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.productwatch.ProcurementPlanQuery;
import com.vladoose.nir.service.productwatch.ProductWatchScheduler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** «Планы закупок» (спека §10.2, §13): список с фильтрами; «Собрать сейчас» — ADMIN. */
@RestController
@RequestMapping("/api/procurement-plans")
public class ProcurementPlanController {

    private final ProcurementPlanQuery query;
    private final ProductWatchScheduler scheduler;

    public ProcurementPlanController(ProcurementPlanQuery query, ProductWatchScheduler scheduler) {
        this.query = query; this.scheduler = scheduler;
    }

    @GetMapping
    public List<PlanPointDto> list(@RequestParam(required = false) Long group, @RequestParam(required = false) String region,
                                   @RequestParam(defaultValue = "false") boolean soon, @RequestParam(defaultValue = "false") boolean fresh,
                                   @RequestParam(defaultValue = "false") boolean all, @RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "month") String sort, @RequestParam(defaultValue = "300") int limit) {
        return query.list(new ProcurementPlanQuery.Filter(group, region, soon, fresh, all, q, sort, limit));
    }

    @PostMapping("/collect")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductWatchScheduler.Status collect() {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        return scheduler.startPlans();
    }
}
