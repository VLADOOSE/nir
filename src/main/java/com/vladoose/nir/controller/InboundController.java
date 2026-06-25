package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.dto.response.InboundEmailResponse;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.InboundEmail;
import com.vladoose.nir.entity.InboundStatus;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.service.MailPollScheduler;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inbound")
public class InboundController {

    private final InboundEmailRepository repository;
    private final MailPollScheduler scheduler;
    private final PrivateRequestImportService importService;

    public InboundController(InboundEmailRepository repository,
                             MailPollScheduler scheduler,
                             PrivateRequestImportService importService) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.importService = importService;
    }

    @GetMapping
    public List<InboundEmailResponse> list() {
        return repository.findAllByOrderByReceivedAtDesc().stream().map(this::toResponse).toList();
    }

    @PostMapping("/poll")
    @PreAuthorize("hasRole('ADMIN')")
    public PollResultResponse poll() {
        return scheduler.run();
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse preview(@PathVariable Long id) {
        InboundEmail e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Письмо не найдено: id=" + id));
        if (e.getAttachment() == null) {
            throw new IllegalArgumentException("У письма нет Excel-вложения");
        }
        return importService.preview(e.getAttachment(), e.getAttachmentName());
    }

    @PostMapping("/{id}/processed")
    @PreAuthorize("hasRole('ADMIN')")
    public void markProcessed(@PathVariable Long id) {
        InboundEmail e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Письмо не найдено: id=" + id));
        e.setStatus(InboundStatus.PROCESSED);
        repository.save(e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException ex) {
        return Map.of("message", ex.getMessage());
    }

    private InboundEmailResponse toResponse(InboundEmail e) {
        InboundEmailResponse r = new InboundEmailResponse();
        r.setId(e.getId());
        r.setFromAddress(e.getFromAddress());
        r.setSubject(e.getSubject());
        r.setReceivedAt(e.getReceivedAt());
        r.setType(e.getType() == null ? null : e.getType().name());
        r.setMatchedPriceRequestId(e.getMatchedPriceRequestId());
        r.setAttachmentName(e.getAttachmentName());
        r.setHasAttachment(e.getAttachment() != null);
        r.setExcerpt(e.getExcerpt());
        r.setStatus(e.getStatus() == null ? null : e.getStatus().name());
        return r;
    }
}
