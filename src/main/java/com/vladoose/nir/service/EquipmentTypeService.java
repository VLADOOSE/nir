package com.vladoose.nir.service;

import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.EquipmentTypeRepository;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EquipmentTypeService {

    private final EquipmentTypeRepository repository;
    private final MedEquipmentRepository medEquipmentRepository;
    private final TenderLotRepository tenderLotRepository;

    public EquipmentTypeService(EquipmentTypeRepository repository,
                                MedEquipmentRepository medEquipmentRepository,
                                TenderLotRepository tenderLotRepository) {
        this.repository = repository;
        this.medEquipmentRepository = medEquipmentRepository;
        this.tenderLotRepository = tenderLotRepository;
    }

    public List<EquipmentType> findAll() {
        return repository.findAll();
    }

    public EquipmentType findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Тип не найден: " + id));
    }

    @Transactional
    public EquipmentType save(EquipmentType type) {
        return repository.save(type);
    }

    @Transactional
    public void deleteById(Long id) {
        if (!medEquipmentRepository.findByEquipmentTypeId(id).isEmpty()) {
            throw new BadRequestException("Невозможно удалить: тип используется в оборудовании");
        }
        if (!tenderLotRepository.findByEquipmentTypeId(id).isEmpty()) {
            throw new BadRequestException("Невозможно удалить: тип используется в лотах тендеров");
        }
        repository.deleteById(id);
    }
}
