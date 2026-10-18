package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.DealEventRequest;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.dto.response.DealTimelineResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTimelineService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

/** Лента и события сделки (спека 2026-10-18 §6): частная заявка; тендер — задача 6. */
@RestController
public class DealController {

    private final DealTimelineService timeline;
    private final DealEventService events;
    private final PrivateRequestService privateRequests;

    public DealController(DealTimelineService timeline, DealEventService events, PrivateRequestService privateRequests) {
        this.timeline = timeline;
        this.events = events;
        this.privateRequests = privateRequests;
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
}
