package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AnnouncementResult;
import com.vladoose.nir.dto.response.TenderSearchStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.service.productwatch.AnnouncementService;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import com.vladoose.nir.service.productwatch.TenderSearchService;
import com.vladoose.nir.util.ErrorText;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** «Найти тендеры» (спека §11, §13): поиск по заявке, «Итоги ›», «Завести в АИС». */
@RestController
public class TenderSearchController {

    private static final Pattern ANNO = Pattern.compile("^\\d{5,12}-\\d{1,3}$");

    private final TenderSearchService service;
    private final AnnouncementService announcements;
    private final ProductWatchService watch;

    public TenderSearchController(TenderSearchService service, AnnouncementService announcements, ProductWatchService watch) {
        this.service = service; this.announcements = announcements; this.watch = watch;
    }

    @PostMapping("/api/private-requests/{id}/tender-search")
    public TenderSearchStatus start(@PathVariable Long id) { return service.start(id); }

    @GetMapping("/api/private-requests/{id}/tender-search")
    public TenderSearchStatus status(@PathVariable Long id) { return service.status(id); }

    @PostMapping("/api/goszakup/announcements/{numberAnno}/import")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Long> importAnnouncement(@PathVariable String numberAnno) {
        check(numberAnno);
        return Map.of("tenderId", upstream(() -> watch.importAnnouncement(numberAnno)));
    }

    @GetMapping("/api/goszakup/announcements/{numberAnno}/result")
    public AnnouncementResult result(@PathVariable String numberAnno) {
        check(numberAnno);
        return upstream(() -> announcements.result(numberAnno));
    }

    private static void check(String numberAnno) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        if (numberAnno == null || !ANNO.matcher(numberAnno).matches()) throw new BadRequestException("Неверный номер объявления");
    }

    /** Отказ площадки — 502 со своим текстом (без адреса и токена), а не 500. */
    private static <T> T upstream(Supplier<T> s) {
        try {
            return s.get();
        } catch (IllegalStateException e) {   // GoszakupCallException — его наследник
            throw new UpstreamException("goszakup не ответил: " + ErrorText.of(e));
        }
    }
}
