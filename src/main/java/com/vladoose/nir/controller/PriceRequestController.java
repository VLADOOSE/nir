package com.vladoose.nir.controller;

import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.PriceRequestService;
import com.vladoose.nir.service.TenderLotService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/price-requests")
public class PriceRequestController {

    private final PriceRequestService service;
    private final TenderLotService tenderLotService;
    private final MedEquipmentService medEquipmentService;
    private final DistributorService distributorService;

    public PriceRequestController(PriceRequestService service,
                                  TenderLotService tenderLotService,
                                  MedEquipmentService medEquipmentService,
                                  DistributorService distributorService) {
        this.service = service;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
    }

    @GetMapping
    public List<PriceRequest> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public PriceRequest findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping("/by-lot/{lotId}")
    public List<PriceRequest> findByLot(@PathVariable Long lotId) {
        return service.findByTenderLotId(lotId);
    }

    @GetMapping("/by-distributor/{distributorId}")
    public List<PriceRequest> findByDistributor(@PathVariable Long distributorId) {
        return service.findByDistributorId(distributorId);
    }

    @PostMapping
    public PriceRequest create(@Valid @RequestBody PriceRequest priceRequest) {
        priceRequest.setTenderLot(tenderLotService.findById(priceRequest.getTenderLot().getId()));
        priceRequest.setMedEquipment(medEquipmentService.findById(priceRequest.getMedEquipment().getId()));
        priceRequest.setDistributor(distributorService.findById(priceRequest.getDistributor().getId()));
        return service.save(priceRequest);
    }

    @PutMapping("/{id}")
    public PriceRequest update(@PathVariable Long id, @Valid @RequestBody PriceRequest priceRequest) {
        PriceRequest existing = service.findById(id);
        existing.setTenderLot(priceRequest.getTenderLot());
        existing.setMedEquipment(priceRequest.getMedEquipment());
        existing.setDistributor(priceRequest.getDistributor());
        existing.setStatus(priceRequest.getStatus());
        existing.setSentAt(priceRequest.getSentAt());
        existing.setResponsePrice(priceRequest.getResponsePrice());
        existing.setResponseDate(priceRequest.getResponseDate());
        existing.setResponseNote(priceRequest.getResponseNote());
        return service.save(existing);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }
}
