package com.vladoose.nir.service;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.FacilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FacilityService {

    private final FacilityRepository repository;

    public FacilityService(FacilityRepository repository) {
        this.repository = repository;
    }

    public List<Facility> findAll() {
        return repository.findAll();
    }

    public Facility findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Учреждение не найдено: id=" + id));
    }

    @Transactional
    public Facility save(Facility facility) {
        return repository.save(facility);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
