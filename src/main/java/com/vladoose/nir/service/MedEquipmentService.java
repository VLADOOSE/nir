package com.vladoose.nir.service;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.repository.MedEquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class MedEquipmentService {
    private final MedEquipmentRepository repo;
    public MedEquipmentService(MedEquipmentRepository repo) { this.repo = repo; }
    public List<MedEquipment> findAll() { return repo.findAll(); }
    public MedEquipment findById(Long id) { return repo.findById(id).orElseThrow(() -> new RuntimeException("Not found")); }
    public MedEquipment create(MedEquipment e) { e.setMedEquipId(null); return repo.save(e); }
    public MedEquipment update(Long id, MedEquipment e) { MedEquipment ex = findById(id); ex.setName(e.getName()); ex.setSpec(e.getSpec()); ex.setCost(e.getCost()); ex.setManufact(e.getManufact()); return repo.save(ex); }
    public void delete(Long id) { repo.deleteById(id); }
}