package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ReplyColumnRole;
import com.vladoose.nir.dto.request.ReplyRateRequest;
import com.vladoose.nir.dto.response.SupplierReplyView;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.SupplierReplyFile;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import com.vladoose.nir.service.supplierreply.SupplierReplyService;
import com.vladoose.nir.service.supplierreply.SupplierReplyViewBuilder;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Экран «Ответ поставщика» (спека 2026-10-15 §4.1, §5): читать — любой вошедший, писать — администратор. */
@RestController
@RequestMapping("/api/price-requests/{id}")
public class SupplierReplyController {

    private final SupplierReplyViewBuilder views;
    private final SupplierReplyService service;
    private final SupplierReplyFileRepository files;
    private final ReplyPdfPages pdf;

    public SupplierReplyController(SupplierReplyViewBuilder views, SupplierReplyService service,
                                   SupplierReplyFileRepository files, ReplyPdfPages pdf) {
        this.views = views;
        this.service = service;
        this.files = files;
        this.pdf = pdf;
    }

    @GetMapping("/reply")
    public SupplierReplyView reply(@PathVariable Long id, @RequestParam(required = false) Long fileId) {
        return views.view(id, fileId);
    }

    @GetMapping("/files/{fileId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long fileId) {
        SupplierReplyFile f = file(id, fileId);
        if (f.getData() == null) throw new NotFoundException("Файл больше предела — откройте его в почте");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType(f)))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.getData());
    }

    /** Страница PDF картинкой, n — с 1. */
    @GetMapping("/files/{fileId}/pages/{n}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> page(@PathVariable Long id, @PathVariable Long fileId, @PathVariable int n) throws IOException {
        SupplierReplyFile f = file(id, fileId);
        if (f.getKind() != ReplyFileKind.PDF || f.getData() == null) throw new NotFoundException("Это не PDF");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(pdf.page(f.getData(), n - 1));
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Файл не выбран");
        String user = SecurityContextHolder.getContext().getAuthentication().getName();
        SupplierReplyFile f = service.upload(id, file.getOriginalFilename(), file.getContentType(), file.getBytes(), user);
        return views.view(id, f.getId());
    }

    @PostMapping("/files/{fileId}/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView apply(@PathVariable Long id, @PathVariable Long fileId) {
        service.applyFile(id, fileId);
        return views.view(id, fileId);
    }

    @PutMapping("/files/{fileId}/columns")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView columns(@PathVariable Long id, @PathVariable Long fileId, @RequestBody List<ReplyColumnRole> roles) {
        service.setColumns(id, fileId, roles);
        return views.view(id, fileId);
    }

    /** Курс для файла в чужой валюте: цены файла × курс → в ответ с 💡; пустой курс — снять. */
    @PutMapping("/files/{fileId}/rate")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView rate(@PathVariable Long id, @PathVariable Long fileId, @RequestBody ReplyRateRequest body) {
        service.setRate(id, fileId, body == null ? null : body.rate());
        return views.view(id, fileId);
    }

    @DeleteMapping("/files/{fileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView delete(@PathVariable Long id, @PathVariable Long fileId) {
        service.deleteFile(id, fileId);
        return views.view(id, null);
    }

    @PostMapping("/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView verify(@PathVariable Long id) {
        service.verify(id);
        return views.view(id, null);
    }

    private SupplierReplyFile file(Long id, Long fileId) {
        service.guard(id);
        return files.findById(fileId).filter(f -> id.equals(f.getPriceRequestId()))
                .orElseThrow(() -> new NotFoundException("Файл ответа не найден: " + fileId));
    }

    /** Тип отдаётся только из белого списка; остальное — octet-stream (и всегда attachment + nosniff). */
    static String contentType(SupplierReplyFile f) {
        String n = f.getFileName().toLowerCase(Locale.ROOT);
        return switch (f.getKind()) {
            case PDF -> "application/pdf";
            case IMAGE -> n.endsWith(".png") ? "image/png" : n.endsWith(".gif") ? "image/gif" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
            default -> "application/octet-stream";
        };
    }
}
