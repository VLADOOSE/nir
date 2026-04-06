package com.vladoose.nir.service;

import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TenderLotService {

    private final TenderLotRepository repository;

    public TenderLotService(TenderLotRepository repository) {
        this.repository = repository;
    }

    public List<TenderLot> findByTenderId(Long tenderId) {
        return repository.findByTenderId(tenderId);
    }

    public TenderLot findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + id));
    }

    @Transactional
    public TenderLot save(TenderLot lot) {
        return repository.save(lot);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
