package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.MarketHintsRequest;
import com.vladoose.nir.dto.response.MarketHint;
import com.vladoose.nir.dto.response.MarketPriceResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.market.MarketPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Цены рынка (спека 2026-10-22-tender-results-market-prices §13): только KZ; лот — с гардом рынка его тендера или заявки. */
@RestController
@RequestMapping("/api/market-prices")
public class MarketPriceController {

    private final MarketPriceService service;
    private final TenderLotRepository lots;
    private final TenderService tenders;
    private final PrivateRequestService privateRequests;

    public MarketPriceController(MarketPriceService service, TenderLotRepository lots, TenderService tenders,
                                 PrivateRequestService privateRequests) {
        this.service = service;
        this.lots = lots;
        this.tenders = tenders;
        this.privateRequests = privateRequests;
    }

    private static void kzOnly() {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
    }

    @GetMapping
    public MarketPriceResponse get(@RequestParam(required = false) Long lotId, @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String code, @RequestParam(required = false) String unit,
                                   @RequestParam(required = false) String filter) {
        kzOnly();
        if (lotId != null) {
            TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден"));
            Tender t = lot.getTender();
            if (t.getSource() == Source.PRIVATE_REQUEST) privateRequests.findById(t.getId());
            else tenders.findPublic(t.getId());
            return service.forLot(lotId, code, unit, filter);
        }
        if (q == null || q.strip().length() < 2) throw new BadRequestException("Укажите лот или текст для поиска");
        return service.forText(q.strip(), code, unit, filter);
    }

    @PostMapping("/hints")
    public List<MarketHint> hints(@RequestBody MarketHintsRequest req) {
        kzOnly();
        return service.hints(req.texts() == null ? List.of() : req.texts());
    }
}
