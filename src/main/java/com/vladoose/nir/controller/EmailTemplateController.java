package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.EmailTemplateRequest;
import com.vladoose.nir.dto.response.EmailTemplateResponse;
import com.vladoose.nir.service.EmailTemplateService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/email-template")
public class EmailTemplateController {

    private final EmailTemplateService service;

    public EmailTemplateController(EmailTemplateService service) {
        this.service = service;
    }

    /** Текущий шаблон активного рынка (или дефолт). */
    @GetMapping
    public EmailTemplateResponse get() {
        return service.current();
    }

    /** Зашитый дефолт активного рынка (для «Сбросить»). */
    @GetMapping("/default")
    public EmailTemplateResponse getDefault() {
        return service.defaults();
    }

    /** Сохранить шаблон активного рынка (upsert). */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EmailTemplateResponse put(@Valid @RequestBody EmailTemplateRequest req) {
        return service.save(req.getSubject(), req.getBody());
    }
}
