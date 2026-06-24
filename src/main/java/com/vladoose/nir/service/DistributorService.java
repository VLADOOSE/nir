package com.vladoose.nir.service;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.DistributorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DistributorService {

    private final DistributorRepository repository;

    public DistributorService(DistributorRepository repository) {
        this.repository = repository;
    }

    public List<Distributor> findAll() {
        return repository.findAll();
    }

    public Distributor findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Дистрибьютор не найден: id=" + id));
    }

    @Transactional
    public Distributor save(Distributor distributor) {
        distributor.setMarket(com.vladoose.nir.context.MarketContext.get());
        return repository.save(distributor);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
