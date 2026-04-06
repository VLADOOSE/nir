package com.vladoose.nir.service;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TenderService {

    private final TenderRepository repository;

    public TenderService(TenderRepository repository) {
        this.repository = repository;
    }

    public List<Tender> findAll() {
        return repository.findAll();
    }

    public Tender findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Тендер не найден: id=" + id));
    }

    @Transactional
    public Tender save(Tender tender) {
        return repository.save(tender);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
