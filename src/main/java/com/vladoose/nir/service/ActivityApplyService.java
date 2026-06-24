package com.vladoose.nir.service;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ActivityApplyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ActivityApplyService {

    private final ActivityApplyRepository repository;

    public ActivityApplyService(ActivityApplyRepository repository) {
        this.repository = repository;
    }

    public List<ActivityApply> findAll() {
        return repository.findAll();
    }

    public List<ActivityApply> findByTenderId(Long tenderId) {
        return repository.findByTenderId(tenderId);
    }

    public ActivityApply findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена: id=" + id));
    }

    @Transactional
    public ActivityApply save(ActivityApply apply) {
        if (apply.getId() == null) {
            apply.setMarket(com.vladoose.nir.context.MarketContext.get());
        }
        return repository.save(apply);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
