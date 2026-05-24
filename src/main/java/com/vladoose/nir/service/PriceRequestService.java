package com.vladoose.nir.service;

import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PriceRequestService {

    private final PriceRequestRepository repository;

    public PriceRequestService(PriceRequestRepository repository) {
        this.repository = repository;
    }

    public List<PriceRequest> findAll() {
        return repository.findAll();
    }

    public PriceRequest findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Запрос КП не найден: id=" + id));
    }

    public List<PriceRequest> findByTenderId(Long tenderId) {
        return repository.findByTenderId(tenderId);
    }

    public List<PriceRequest> findByDistributorId(Long distributorId) {
        return repository.findByDistributorId(distributorId);
    }

    public List<PriceRequest> findByStatus(String status) {
        return repository.findByStatus(status);
    }

    @Transactional
    public PriceRequest save(PriceRequest priceRequest) {
        return repository.save(priceRequest);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
