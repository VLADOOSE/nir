package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ChatNotClientRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.ChatAttachment;
import com.vladoose.nir.integration.whatsapp.WhatsappChatScheduler;
import com.vladoose.nir.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** «Чаты» (спека whatsapp-chats §8). Чтение — любому вошедшему, запись — ADMIN, как везде в АИС. */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService service;
    private final WhatsappChatScheduler scheduler;

    public ChatController(ChatService service, WhatsappChatScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @GetMapping
    public List<ChatListItemResponse> list(@RequestParam(defaultValue = "ALL") String filter,
                                           @RequestParam(required = false) String q) {
        return service.list(filter, q);
    }

    @GetMapping("/status")
    public WhatsappStatusResponse status() {
        return scheduler.status();
    }

    @GetMapping("/{id}")
    public ChatResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/messages")
    public List<ChatMessageResponse> messages(@PathVariable Long id, @RequestParam(required = false) Long before,
                                              @RequestParam(defaultValue = "50") int limit) {
        return service.messages(id, before, limit);
    }

    /** Inline — только безопасные картинки; остальное (включая html/svg) — скачивание, чтобы файл не исполнился в АИС. */
    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        ChatAttachment a = service.attachment(id, attachmentId);
        String imageType = ChatService.safeImageType(a.getMimeType());
        boolean inline = imageType != null;
        String name = a.getFileName() == null || a.getFileName().isBlank() ? "file" : a.getFileName();
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(inline ? MediaType.parseMediaType(imageType) : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(a.getContent());
    }

    @PostMapping("/{id}/attachments/{attachmentId}/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse preview(@PathVariable Long id, @PathVariable Long attachmentId) {
        return service.previewAttachment(id, attachmentId);
    }

    @PostMapping("/{id}/lead")
    @PreAuthorize("hasRole('ADMIN')")
    public ChatResponse createLead(@PathVariable Long id) {
        return service.createLead(id, currentUser());
    }

    @PostMapping("/{id}/not-client")
    @PreAuthorize("hasRole('ADMIN')")
    public ChatResponse notClient(@PathVariable Long id, @Valid @RequestBody ChatNotClientRequest req) {
        return service.setNotClient(id, req.getValue());
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
