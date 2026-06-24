package com.vladoose.nir.service;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ApplyItemRepository;
import com.vladoose.nir.repository.MedEquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MedEquipmentService {

    private final MedEquipmentRepository repository;
    private final ApplyItemRepository applyItemRepository;

    public MedEquipmentService(MedEquipmentRepository repository, ApplyItemRepository applyItemRepository) {
        this.repository = repository;
        this.applyItemRepository = applyItemRepository;
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
        equipment.setMarket(com.vladoose.nir.context.MarketContext.get());
        return repository.save(equipment);
    }

    @Transactional
    public void deleteById(Long id) {
        if (applyItemRepository.existsByMedEquipmentId(id)) {
            throw new BadRequestException("Невозможно удалить оборудование: оно используется в позициях заявок.");
        }
        repository.deleteById(id);
    }

    public List<MedEquipment> findMatchingForLot(TenderLot lot) {
        Long typeId = lot.getEquipmentType() != null ? lot.getEquipmentType().getId() : null;

        return repository.findMatchingEquipment(
                typeId,
                lot.getMaxLengthMm(),
                lot.getMaxWidthMm(),
                lot.getMaxHeightMm(),
                lot.getMaxWeightKg()
        );
    }
}
