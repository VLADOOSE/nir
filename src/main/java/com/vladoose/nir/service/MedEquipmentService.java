package com.vladoose.nir.service;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MedEquipmentService {

    private final MedEquipmentRepository repository;

    public MedEquipmentService(MedEquipmentRepository repository) {
        this.repository = repository;
    }

    public List<MedEquipment> findAll() {
        return repository.findAll();
    }

    public MedEquipment findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено: id=" + id));
    }

    @Transactional
    public MedEquipment save(MedEquipment equipment) {
        return repository.save(equipment);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }

    public List<MedEquipment> findMatchingForLot(TenderLot lot) {
        Integer maxCost = lot.getMaxCost() != null
                ? lot.getMaxCost().intValue()
                : null;

        return repository.findMatchingEquipment(
                lot.getEquipType(),
                lot.getMaxLengthMm(),
                lot.getMaxWidthMm(),
                lot.getMaxHeightMm(),
                lot.getMaxWeightKg(),
                maxCost
        );
    }
}
