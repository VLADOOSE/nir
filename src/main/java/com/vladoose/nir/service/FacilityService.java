package com.vladoose.nir.service;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FacilityService {

    private final FacilityRepository repository;
    private final TenderRepository tenderRepository;

    public FacilityService(FacilityRepository repository, TenderRepository tenderRepository) {
        this.repository = repository;
        this.tenderRepository = tenderRepository;
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
        if (facility.getId() == null) {
            facility.setMarket(com.vladoose.nir.context.MarketContext.get());
        }
        return repository.save(facility);
    }

    @Transactional
    public void deleteById(Long id) {
        if (tenderRepository.existsByFacilityId(id)) {
            throw new BadRequestException("Невозможно удалить учреждение: с ним связаны тендеры. Сначала удалите тендеры.");
        }
        repository.deleteById(id);
    }
}
