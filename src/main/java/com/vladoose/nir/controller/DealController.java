package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.DealEventRequest;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.dto.response.DealTimelineResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.deal.TenderProgressBuilder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Лента, события и этапы сделки (спека 2026-10-18 §6): частная заявка и тендер. */
@RestController
public class DealController {

    private final DealTimelineService timeline;
    private final DealEventService events;
    private final PrivateRequestService privateRequests;
    private final TenderService tenders;
    private final TenderProgressBuilder tenderProgress;

    public DealController(DealTimelineService timeline, DealEventService events, PrivateRequestService privateRequests,
                          TenderService tenders, TenderProgressBuilder tenderProgress) {
        this.timeline = timeline;
        this.events = events;
        this.privateRequests = privateRequests;
        this.tenders = tenders;
        this.tenderProgress = tenderProgress;
    }

    @GetMapping("/api/private-requests/{id}/timeline")
    public DealTimelineResponse privateTimeline(@PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
            @RequestParam(defaultValue = "50") int limit) {
        return timeline.forPrivateRequest(id, before, limit);
    }

    @PostMapping("/api/private-requests/{id}/events")
    @PreAuthorize("hasRole('ADMIN')")
    public DealTimelineItem privateEvent(@PathVariable Long id, @RequestBody DealEventRequest body) {
        Tender t = privateRequests.findById(id);
        return timeline.toItem(events.note(t.getId(), body.type(), body.text()));
    }

    @GetMapping("/api/tenders/{id}/progress")
    public DealProgress tenderProgress(@PathVariable Long id) {
        Tender t = tenders.findPublic(id);
        return tenderProgress.build(t, LocalDateTime.now((t.getMarket() == null ? MarketContext.get() : t.getMarket()).zone()));
    }

    @GetMapping("/api/tenders/{id}/timeline")
    public DealTimelineResponse tenderTimeline(@PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
            @RequestParam(defaultValue = "50") int limit) {
        return timeline.forTender(id, before, limit);
    }

    @PostMapping("/api/tenders/{id}/events")
    @PreAuthorize("hasRole('ADMIN')")
    public DealTimelineItem tenderEvent(@PathVariable Long id, @RequestBody DealEventRequest body) {
        return timeline.toItem(events.note(tenders.findPublic(id).getId(), body.type(), body.text()));
    }
}
