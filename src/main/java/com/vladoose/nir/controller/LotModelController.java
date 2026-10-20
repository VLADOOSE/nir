package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.LotModelNoteRequest;
import com.vladoose.nir.dto.request.LotModelRequest;
import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.dto.response.LotModelSearchResponse;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.TenderLotMapper;
import com.vladoose.nir.service.lotmodel.LotModelFit;
import com.vladoose.nir.service.lotmodel.LotModelSearch;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.lotmodel.LotModelViews;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** «Модель в работе» лота (спека 2026-10-20-lot-model-in-work §5.5, §6). */
@RestController
@RequestMapping("/api/lots")
public class LotModelController {

    private final LotModelService lotModel;
    private final LotModelSearch search;
    private final LotModelFit fit;
    private final LotModelViews views;
    private final TenderLotMapper mapper;

    public LotModelController(LotModelService lotModel, LotModelSearch search, LotModelFit fit, LotModelViews views, TenderLotMapper mapper) {
        this.lotModel = lotModel;
        this.search = search;
        this.fit = fit;
        this.views = views;
        this.mapper = mapper;
    }

    @GetMapping("/{id}/model-search")
    public LotModelSearchResponse search(@PathVariable Long id, @RequestParam(required = false) String q) {
        return search.search(id, q);
    }

    @PostMapping("/{id}/model")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse pin(@PathVariable Long id, @RequestBody LotModelRequest req) {
        boolean site = req.getSiteProductId() != null;
        boolean reg = req.getRegNumber() != null && !req.getRegNumber().isBlank();
        if (site == reg) throw new BadRequestException("Укажите либо товар сайта, либо № РУ");
        TenderLot lot = site ? lotModel.pinSiteProduct(id, req.getSiteProductId(), req.getNote())
                : lotModel.pinRegistry(id, req.getRegNumber().strip(), req.getNote());
        if (reg) fit.prefetchCard(req.getRegNumber().strip());       // сеть — после транзакции; сбой не мешает
        return views.enrich(mapper.toResponse(lot), lot);
    }

    @PutMapping("/{id}/model-note")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse note(@PathVariable Long id, @RequestBody LotModelNoteRequest req) {
        TenderLot lot = lotModel.setNote(id, req.getNote());
        return views.enrich(mapper.toResponse(lot), lot);
    }

    @DeleteMapping("/{id}/model")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse clear(@PathVariable Long id) {
        TenderLot lot = lotModel.clearModel(id);
        return views.enrich(mapper.toResponse(lot), lot);
    }

    @GetMapping("/{id}/model-fit")
    public LotModelFitResponse modelFit(@PathVariable Long id) {
        return fit.fit(id);
    }
}
