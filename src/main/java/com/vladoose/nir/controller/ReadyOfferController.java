package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ClientOfferResponse;
import com.vladoose.nir.dto.response.OfferFileCandidate;
import com.vladoose.nir.dto.response.OfferFileView;
import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferFile;
import com.vladoose.nir.entity.ClientOfferStatus;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.service.ClientOfferMapper;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.ReadyOfferService;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Готовое КП (спека 2026-10-19 §6): читать и скачивать — любой вошедший, заносить и отмечать — администратор. */
@RestController
public class ReadyOfferController {

    private final ReadyOfferService service;
    private final ClientOfferService offers;
    private final ClientOfferMapper mapper;
    private final ReplyPdfPages pdf;

    public ReadyOfferController(ReadyOfferService service, ClientOfferService offers, ClientOfferMapper mapper, ReplyPdfPages pdf) {
        this.service = service;
        this.offers = offers;
        this.mapper = mapper;
        this.pdf = pdf;
    }

    @GetMapping("/api/private-requests/{id}/offer-file/candidates")
    public List<OfferFileCandidate> candidates(@PathVariable Long id) {
        return service.candidates(id);
    }

    @PostMapping(value = "/api/private-requests/{id}/offer-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse attach(@PathVariable Long id,
                                      @RequestParam(value = "file", required = false) MultipartFile file,
                                      @RequestParam(value = "attachmentId", required = false) Long attachmentId,
                                      @RequestParam("status") String status,
                                      @RequestParam("sentOn") String sentOn) throws IOException {
        ReadyOfferService.Upload upload = file == null || file.isEmpty() ? null
                : new ReadyOfferService.Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return full(service.attach(id, upload, attachmentId, status(status), date(sentOn), CurrentUser.name()));
    }

    @PostMapping("/api/private-requests/{id}/offer-hints/{attachmentId}/dismiss")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> dismiss(@PathVariable Long id, @PathVariable Long attachmentId) {
        service.dismiss(id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/private-requests/{id}/offer-hints/{attachmentId}/mark-sent")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse markSent(@PathVariable Long id, @PathVariable Long attachmentId) {
        return full(service.markSent(id, attachmentId));
    }

    @GetMapping("/api/client-offers/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        ClientOfferFile f = service.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType(f.getKind(), f.getFileName())))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.getContent());
    }

    @GetMapping("/api/client-offers/{id}/file/view")
    public OfferFileView view(@PathVariable Long id) {
        ClientOfferFile f = service.file(id);
        return new OfferFileView(f.getKind().name(), f.getPages(), f.getTables() == null ? List.of() : f.getTables());
    }

    /** Страница PDF картинкой, n — с 1 (до 30, как у ответов поставщиков). */
    @GetMapping("/api/client-offers/{id}/file/pages/{n}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> page(@PathVariable Long id, @PathVariable int n) throws IOException {
        ClientOfferFile f = service.file(id);
        if (f.getKind() != ReplyFileKind.PDF) throw new NotFoundException("Это не PDF");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(pdf.page(f.getContent(), n - 1));
    }

    private ClientOfferResponse full(ClientOffer o) {
        return mapper.toResponse(o, offers.calculate(o), offers.fileBaseName(o));
    }

    /** Тип — только из белого списка; остальное — octet-stream (и всегда attachment + nosniff). */
    public static String contentType(ReplyFileKind kind, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case PDF -> "application/pdf";
            case IMAGE -> n.endsWith(".png") ? "image/png" : n.endsWith(".gif") ? "image/gif" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
            default -> "application/octet-stream";
        };
    }

    private static ClientOfferStatus status(String s) {
        try {
            return ClientOfferStatus.valueOf(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Статус готового КП — «Отправлено» или «Клиент согласился»");
        }
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequestException("Дата отправки — в виде ГГГГ-ММ-ДД");
        }
    }
}
