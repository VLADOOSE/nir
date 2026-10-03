package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ClientOfferStatusRequest;
import com.vladoose.nir.dto.request.ClientOfferUpdateRequest;
import com.vladoose.nir.dto.response.ClientOfferListItemResponse;
import com.vladoose.nir.dto.response.ClientOfferResponse;
import com.vladoose.nir.dto.response.PreviewPagesResponse;
import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.ClientOfferMapper;
import com.vladoose.nir.service.ClientOfferService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** «КП клиентам» (спека §10). Чтение и скачивание — любому вошедшему, запись — ADMIN, как везде в АИС. */
@RestController
@RequestMapping("/api/client-offers")
public class ClientOfferController {

    static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ClientOfferService service;
    private final ClientOfferMapper mapper;

    public ClientOfferController(ClientOfferService service, ClientOfferMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<ClientOfferListItemResponse> list(@RequestParam(defaultValue = "ALL") String status,
                                                  @RequestParam(required = false) String q) {
        return service.list(parseStatuses(status), q).stream().map(mapper::toListItem).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse create() {
        return full(service.create(currentUser()));
    }

    @GetMapping("/{id}")
    public ClientOfferResponse get(@PathVariable Long id) {
        return full(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse update(@PathVariable Long id, @Valid @RequestBody ClientOfferUpdateRequest req) {
        return full(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse duplicate(@PathVariable Long id) {
        return full(service.duplicate(id, currentUser()));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse status(@PathVariable Long id, @Valid @RequestBody ClientOfferStatusRequest req) {
        return full(service.setStatus(id, req.getStatus()));
    }

    @GetMapping("/{id}/preview")
    public PreviewPagesResponse preview(@PathVariable Long id) {
        return service.preview(id);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        return file(service.pdf(id), MediaType.APPLICATION_PDF);
    }

    @GetMapping("/{id}/docx")
    public ResponseEntity<byte[]> docx(@PathVariable Long id) {
        return file(service.docx(id), DOCX);
    }

    private ClientOfferResponse full(ClientOffer o) {
        return mapper.toResponse(o, service.calculate(o), service.fileBaseName(o));
    }

    /** Только скачивание (attachment, имя по RFC 5987 — кириллица цела), без угадывания типа и без кеша. */
    private static ResponseEntity<byte[]> file(ClientOfferService.OfferFile file, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(file.bytes());
    }

    private static Set<ClientOfferStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("ALL")) return EnumSet.allOf(ClientOfferStatus.class);
        Set<ClientOfferStatus> set = EnumSet.noneOf(ClientOfferStatus.class);
        for (String s : raw.split(",")) {
            try {
                set.add(ClientOfferStatus.valueOf(s.trim()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Неизвестный статус КП: " + s.trim());
            }
        }
        return set;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
