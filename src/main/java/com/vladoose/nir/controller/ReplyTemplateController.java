package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ReplyTemplatesRequest;
import com.vladoose.nir.dto.response.ReplyTemplatesResponse;
import com.vladoose.nir.service.ReplyTemplateService;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** «Система → Шаблоны ответов» (спека 2026-10-19-client-mail-reply §6.4, §8). Только администратор. */
@RestController
@RequestMapping("/api/reply-templates")
public class ReplyTemplateController {

    private final ReplyTemplateService service;

    public ReplyTemplateController(ReplyTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ReplyTemplatesResponse get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ReplyTemplatesResponse save(@RequestBody ReplyTemplatesRequest req) {
        return service.save(req, CurrentUser.name());
    }
}
