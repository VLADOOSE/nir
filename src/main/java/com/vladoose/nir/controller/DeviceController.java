package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.DeviceApproveRequest;
import com.vladoose.nir.dto.response.DeviceListResponse;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.GateCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

/** «Устройства» — кто может открыть ais.westmed.kz (спека device-gate §7, §11). Только ADMIN. */
@RestController
@RequestMapping("/api/devices")
@PreAuthorize("hasRole('ADMIN')")
public class DeviceController {

    private final DeviceGateService gate;

    public DeviceController(DeviceGateService gate) {
        this.gate = gate;
    }

    /** Cookie калитки в этом же запросе — чтобы пометить «это устройство». */
    @GetMapping
    public DeviceListResponse list(@CookieValue(name = GateCookie.NAME, required = false) String token) {
        return gate.list(token, OffsetDateTime.now());
    }

    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("count", gate.pendingCount(OffsetDateTime.now()));
    }

    @PostMapping("/{id}/approve")
    public DeviceResponse approve(@PathVariable Long id, @RequestBody(required = false) DeviceApproveRequest body) {
        return gate.approve(id, body == null ? null : body.getLabel(), currentUser(), OffsetDateTime.now());
    }

    @PostMapping("/{id}/reject")
    public DeviceResponse reject(@PathVariable Long id) {
        return gate.reject(id, currentUser(), OffsetDateTime.now());
    }

    @PostMapping("/{id}/revoke")
    public DeviceResponse revoke(@PathVariable Long id) {
        return gate.revoke(id, currentUser(), OffsetDateTime.now());
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
