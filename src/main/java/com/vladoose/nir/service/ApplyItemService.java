package com.vladoose.nir.service;

import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ApplyItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApplyItemService {

    private final ApplyItemRepository repository;

    public ApplyItemService(ApplyItemRepository repository) {
        this.repository = repository;
    }

    public List<ApplyItem> findByApplyId(Long applyId) {
        return repository.findByApplyId(applyId);
    }

    public ApplyItem findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Позиция заявки не найдена: id=" + id));
    }

    @Transactional
    public ApplyItem save(ApplyItem item) {
        return repository.save(item);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
