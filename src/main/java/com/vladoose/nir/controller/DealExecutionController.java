package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.ExecutionFileCandidate;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.DealFile;
import com.vladoose.nir.entity.DealFileStage;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import com.vladoose.nir.util.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Исполнение сделки (спека 2026-10-19-deal-execution §9): запись — администратор, ответ на запись — вид заявки (раздел,
 * этапы и «Дальше» обновляются разом); скачать файл — любой вошедший. Гард рынка и вида сделки — в сервисе.
 */
@RestController
@RequestMapping("/api/private-requests/{id}/execution")
public class DealExecutionController {

    private final DealExecutionService service;
    private final PrivateRequestViewBuilder views;

    public DealExecutionController(DealExecutionService service, PrivateRequestViewBuilder views) {
        this.service = service;
        this.views = views;
    }

    @PatchMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse field(@PathVariable Long id, @Valid @RequestBody ExecutionFieldUpdate req) {
        return views.build(service.updateField(id, req.getField(), req.getValue()));
    }

    @PutMapping("/marks/{mark}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse mark(@PathVariable Long id, @PathVariable String mark, @RequestBody ExecutionMarkRequest req) {
        return views.build(service.setMark(id, parse(ExecutionMark.class, mark), req.getOn(), req.getNote()));
    }

    @PostMapping("/payments")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse addPayment(@PathVariable Long id, @RequestBody DealPaymentRequest req) {
        return views.build(service.addPayment(id, input(req)));
    }

    @PutMapping("/payments/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse updatePayment(@PathVariable Long id, @PathVariable Long paymentId,
                                                    @RequestBody DealPaymentRequest req) {
        return views.build(service.updatePayment(id, paymentId, input(req)));
    }

    @DeleteMapping("/payments/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deletePayment(@PathVariable Long id, @PathVariable Long paymentId) {
        return views.build(service.deletePayment(id, paymentId));
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse addSupplier(@PathVariable Long id, @RequestBody DealSupplierAddRequest req) {
        return views.build(service.addSupplier(id, req.getDistributorId(), req.getAmount(), req.getCurrency()));
    }

    @PatchMapping("/suppliers/{distributorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse updateSupplier(@PathVariable Long id, @PathVariable Long distributorId,
                                                     @Valid @RequestBody SupplierFieldUpdate req) {
        return views.build(service.updateSupplier(id, distributorId, req.getField(), req.getValue()));
    }

    @PutMapping("/suppliers/{distributorId}/marks/{mark}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse supplierMark(@PathVariable Long id, @PathVariable Long distributorId,
                                                   @PathVariable String mark, @RequestBody ExecutionMarkRequest req) {
        return views.build(service.setSupplierMark(id, distributorId, parse(SupplierMark.class, mark), req.getOn(), req.getNote()));
    }

    @DeleteMapping("/suppliers/{distributorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deleteSupplier(@PathVariable Long id, @PathVariable Long distributorId) {
        return views.build(service.deleteSupplier(id, distributorId));
    }

    @GetMapping("/file-candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ExecutionFileCandidate> candidates(@PathVariable Long id) {
        return service.candidates(id);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse attach(@PathVariable Long id,
                                             @RequestParam(value = "file", required = false) MultipartFile file,
                                             @RequestParam(value = "attachmentId", required = false) Long attachmentId,
                                             @RequestParam("stage") String stage,
                                             @RequestParam(value = "paymentId", required = false) Long paymentId,
                                             @RequestParam(value = "distributorId", required = false) Long distributorId)
            throws IOException {
        DealExecutionService.Upload upload = file == null || file.isEmpty() ? null
                : new DealExecutionService.Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return views.build(service.attach(id, upload, attachmentId, parse(DealFileStage.class, stage), paymentId,
                distributorId, CurrentUser.name()));
    }

    /** Файл: attachment с именем RFC 5987, nosniff, без кеша; тип — только PDF и картинки (как готовое КП). */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> file(@PathVariable Long id, @PathVariable Long fileId) {
        DealFile f = service.file(id, fileId);
        ReplyFileKind kind = SupplierReplyFiles.kind(f.getFileName(), f.getMimeType(), f.getContent());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReadyOfferController.contentType(kind, f.getFileName())))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.getContent());
    }

    @DeleteMapping("/files/{fileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deleteFile(@PathVariable Long id, @PathVariable Long fileId) {
        return views.build(service.deleteFile(id, fileId));
    }

    private static DealExecutionService.PaymentInput input(DealPaymentRequest r) {
        return new DealExecutionService.PaymentInput(r.getDistributorId(), r.getAmount(), r.getPaidOn(), r.getNote());
    }

    /** Неизвестное значение в пути или параметре — 400, а не 500 (решение при планировании 10). */
    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException(type == DealFileStage.class ? "Неизвестное место файла" : "Неизвестная отметка");
        }
    }
}
