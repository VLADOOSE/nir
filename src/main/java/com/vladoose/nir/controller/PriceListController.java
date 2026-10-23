package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PriceHintsRequest;
import com.vladoose.nir.dto.request.PriceListPatch;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.PriceList;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.pricelist.PriceHints;
import com.vladoose.nir.service.pricelist.PriceListService;
import com.vladoose.nir.service.pricelist.PriceListViewBuilder;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** «Прайсы поставщиков» (спека §5, §9): читать — все, загружать и править — администратор. */
@RestController
@RequestMapping("/api/price-lists")
public class PriceListController {

    private final PriceListService service;
    private final PriceListViewBuilder views;
    private final PriceHints hints;

    public PriceListController(PriceListService service, PriceListViewBuilder views, PriceHints hints) {
        this.service = service;
        this.views = views;
        this.hints = hints;
    }

    @GetMapping
    public List<PriceListListItem> list(@RequestParam(required = false) Long supplier) {
        return views.list(supplier);
    }

    @GetMapping("/search")
    public List<PriceHintRow> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "50") int limit) {
        return hints.search(MarketContext.get(), q, limit);
    }

    @PostMapping("/hints")
    public List<PriceHint> hints(@RequestBody PriceHintsRequest in) {
        List<String> texts = in == null || in.texts() == null ? List.of() : in.texts();
        if (texts.size() > PriceHints.TEXTS_MAX) throw new BadRequestException("Не больше 200 позиций за раз");
        return hints.hints(MarketContext.get(), texts.stream().map(PriceHints.Target::text).toList());
    }

    @GetMapping("/rows/{rowId}/history")
    public List<PriceHistoryEntry> history(@PathVariable Long rowId) {
        return hints.history(rowId);
    }

    @GetMapping("/link-candidates")
    public PriceLinkCandidates linkCandidates(@RequestParam(required = false) String q) {
        return hints.linkCandidates(q);
    }

    public record FromInbound(Long distributorId) {}

    @PostMapping("/from-inbound/{fileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView fromInbound(@PathVariable Long fileId, @RequestBody(required = false) FromInbound in) {
        return views.view(service.fromInbound(fileId, in == null ? null : in.distributorId(), CurrentUser.name()));
    }

    @GetMapping("/counts")
    public Map<Long, Long> counts() {
        return service.counts();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView upload(@RequestParam("file") MultipartFile file, @RequestParam("distributorId") Long distributorId) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Файл пустой");
        return views.view(service.upload(distributorId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), CurrentUser.name()));
    }

    @GetMapping("/{id}")
    public PriceListView view(@PathVariable Long id) {
        return views.view(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView patch(@PathVariable Long id, @RequestBody PriceListPatch in) {
        service.patch(id, in, CurrentUser.name());
        return views.view(id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView activate(@PathVariable Long id) {
        service.activate(id, CurrentUser.name());
        return views.view(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/rows")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView addRow(@PathVariable Long id) {
        service.addRow(id);
        return views.view(id);
    }

    @PatchMapping("/{id}/rows/{rowId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView patchRow(@PathVariable Long id, @PathVariable Long rowId, @RequestBody PriceListPatch in) {
        service.patchRow(id, rowId, in);
        return views.view(id);
    }

    @DeleteMapping("/{id}/rows/{rowId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView deleteRow(@PathVariable Long id, @PathVariable Long rowId) {
        service.deleteRow(id, rowId);
        return views.view(id);
    }

    /** Файл прайса: attachment с именем RFC 5987, nosniff, без кеша; тип — только PDF и картинки (как у готового КП). */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        PriceList p = service.guard(id);
        ReplyFileKind kind = SupplierReplyFiles.kind(p.getFileName(), p.getMimeType(), p.getData());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReadyOfferController.contentType(kind, p.getFileName())))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(p.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(p.getData());
    }
}
