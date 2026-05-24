package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.TenderLotRequest;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.TenderLotMapper;
import com.vladoose.nir.service.TenderLotService;
import com.vladoose.nir.service.TenderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lots")
public class TenderLotController {

    private final TenderLotService service;
    private final TenderService tenderService;
    private final TenderLotMapper mapper;

    public TenderLotController(TenderLotService service,
                               TenderService tenderService,
                               TenderLotMapper mapper) {
        this.service = service;
        this.tenderService = tenderService;
        this.mapper = mapper;
    }

    @GetMapping("/{id}")
    public TenderLotResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    public TenderLotResponse create(@Valid @RequestBody TenderLotRequest request) {
        if (request.getTenderId() == null) {
            throw new BadRequestException("Не указан тендер");
        }
        TenderLot lot = mapper.toEntity(request);
        // Replace the stub Tender reference (id only) with a managed entity so JPA can use it
        Tender tender = tenderService.findById(request.getTenderId());
        lot.setTender(tender);
        return mapper.toResponse(service.save(lot));
    }

    @PutMapping("/{id}")
    public TenderLotResponse update(@PathVariable Long id, @Valid @RequestBody TenderLotRequest request) {
        TenderLot existing = service.findById(id);
        mapper.updateEntity(request, existing);
        if (request.getTenderId() != null) {
            // Replace the stub reference with the managed entity
            Tender tender = tenderService.findById(request.getTenderId());
            existing.setTender(tender);
        }
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
