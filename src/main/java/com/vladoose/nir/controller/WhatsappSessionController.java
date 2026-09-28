package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.WhatsappSessionResponse;
import com.vladoose.nir.service.WhatsappSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** «Система → WhatsApp» (спека whatsapp-waha §7, §9): только администратор — QR даёт доступ к рабочему WhatsApp. */
@RestController
@RequestMapping("/api/whatsapp/session")
public class WhatsappSessionController {

    private final WhatsappSessionService service;

    public WhatsappSessionController(WhatsappSessionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public WhatsappSessionResponse get() {
        return service.info();
    }

    /** Картинку отдаёт АИС, получив у WAHA внутри сервера; не кешировать — код живёт 20–60 с. */
    @GetMapping("/qr")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> qr() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(service.qr());
    }

    @PostMapping("/restart")
    @PreAuthorize("hasRole('ADMIN')")
    public WhatsappSessionResponse restart() {
        return service.restart();
    }

    @PostMapping("/logout")
    @PreAuthorize("hasRole('ADMIN')")
    public WhatsappSessionResponse logout() {
        return service.logout();
    }
}
