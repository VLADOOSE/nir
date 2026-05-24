package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.MedEquipmentRequest;
import com.vladoose.nir.dto.response.MedEquipmentResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.mapper.MedEquipmentMapper;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.TenderLotService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment")
public class MedEquipmentController {

    private final MedEquipmentService service;
    private final TenderLotService tenderLotService;
    private final MedEquipmentMapper mapper;

    public MedEquipmentController(MedEquipmentService service,
                                  TenderLotService tenderLotService,
                                  MedEquipmentMapper mapper) {
        this.service = service;
        this.tenderLotService = tenderLotService;
        this.mapper = mapper;
    }

    @GetMapping
    public List<MedEquipmentResponse> findAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public MedEquipmentResponse findById(@PathVariable Long id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public MedEquipmentResponse create(@Valid @RequestBody MedEquipmentRequest request) {
        MedEquipment entity = mapper.toEntity(request);
        return mapper.toResponse(service.save(entity));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MedEquipmentResponse update(@PathVariable Long id, @Valid @RequestBody MedEquipmentRequest request) {
        MedEquipment existing = service.findById(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.deleteById(id);
    }

    @GetMapping("/match/{lotId}")
    public List<MedEquipmentResponse> findMatchingForLot(@PathVariable Long lotId) {
        TenderLot lot = tenderLotService.findById(lotId);
        return mapper.toResponseList(service.findMatchingForLot(lot));
    }
}
