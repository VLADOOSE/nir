package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.dto.response.InboundEmailResponse;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.InboundEmail;
import com.vladoose.nir.entity.InboundStatus;
import com.vladoose.nir.entity.InboundType;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.service.MailPollScheduler;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/inbound")
public class InboundController {

    private final InboundEmailRepository repository;
    private final MailPollScheduler scheduler;
    private final PrivateRequestImportService importService;
    private final SupplierReplyFileRepository replyFiles;

    public InboundController(InboundEmailRepository repository,
                             MailPollScheduler scheduler,
                             PrivateRequestImportService importService,
                             SupplierReplyFileRepository replyFiles) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.importService = importService;
        this.replyFiles = replyFiles;
    }

    /** Последние 300 писем рынка, или одного ящика (страница покажет, что список обрезан). */
    @GetMapping
    public List<InboundEmailResponse> list(@RequestParam(required = false) String mailbox) {
        List<InboundEmail> rows = mailbox == null || mailbox.isBlank()
                ? repository.findTop300ByOrderByReceivedAtDesc()
                : repository.findTop300ByMailboxOrderByReceivedAtDesc(mailbox.trim().toLowerCase(Locale.ROOT));
        // имена файлов ответа поставщика у ответов — одним запросом
        Map<Long, List<String>> names = new HashMap<>();
        List<Long> ids = rows.stream().filter(e -> e.getType() == InboundType.SUPPLIER_RESPONSE).map(InboundEmail::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] r : replyFiles.findNamesByInboundIds(ids)) {
                names.computeIfAbsent(((Number) r[0]).longValue(), k -> new ArrayList<>()).add((String) r[1]);
            }
        }
        return rows.stream().map(e -> {
            InboundEmailResponse r = toResponse(e);
            r.setFiles(names.getOrDefault(e.getId(), List.of()));
            return r;
        }).toList();
    }

    /** Ящики «Входящих» рынка — чипы фильтра (zakup@, info@). */
    @GetMapping("/mailboxes")
    public List<String> mailboxes() {
        return repository.findMailboxes();
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
        r.setMailbox(e.getMailbox());
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
