package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.dto.response.LeadListItemResponse;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.LeadResponseMapper;
import com.vladoose.nir.service.LeadService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** «Обращения» (спека §8). Чтение — любому вошедшему, запись — ADMIN, как везде в АИС. */
@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private final LeadService service;
    private final LeadResponseMapper mapper;

    public LeadController(LeadService service, LeadResponseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<LeadListItemResponse> list(@RequestParam(defaultValue = "NEW,IN_WORK") String status,
                                           @RequestParam(required = false) LeadChannel channel,
                                           @RequestParam(required = false) String q) {
        return service.list(parseStatuses(status), channel, q).stream().map(mapper::toListItem).toList();
    }

    @GetMapping("/count")
    public Map<String, Long> count(@RequestParam(defaultValue = "NEW") LeadStatus status) {
        return Map.of("count", service.count(status));
    }

    @GetMapping("/{id}")
    public LeadCardResponse get(@PathVariable Long id) {
        return card(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse create(@Valid @RequestBody LeadCreateRequest req) {
        return card(service.createManual(req, currentUser()));
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse updateItems(@PathVariable Long id, @Valid @RequestBody LeadItemsUpdate req) {
        return card(service.updateItems(id, req.getItems(), currentUser()));
    }

    @PostMapping("/{id}/take")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse take(@PathVariable Long id) {
        return card(service.take(id, currentUser()));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse close(@PathVariable Long id, @Valid @RequestBody LeadCloseRequest req) {
        return card(service.close(id, req.getReason(), req.getComment(), currentUser()));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse reopen(@PathVariable Long id) {
        return card(service.reopen(id, currentUser()));
    }

    @PostMapping("/{id}/events")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse addEvent(@PathVariable Long id, @Valid @RequestBody LeadEventCreate req) {
        return card(service.addEvent(id, req.getType(), req.getDirection(), req.getBody(), currentUser()));
    }

    private LeadCardResponse card(Lead lead) {
        return mapper.toCard(lead, service.samePhone(lead));
    }

    /** «NEW,IN_WORK» → набор; «ALL» или пусто → все статусы; неизвестное → 400. */
    public static Set<LeadStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("ALL")) {
            return EnumSet.allOf(LeadStatus.class);
        }
        Set<LeadStatus> out = EnumSet.noneOf(LeadStatus.class);
        for (String part : raw.split(",")) {
            try {
                out.add(LeadStatus.valueOf(part.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Неизвестный статус: " + part.trim());
            }
        }
        return out;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
