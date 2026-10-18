package com.vladoose.nir.service;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.service.deal.ApplyStatusLabels;
import com.vladoose.nir.service.deal.DealEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ActivityApplyService {

    private final ActivityApplyRepository repository;
    private final DealEventService dealEvents;

    public ActivityApplyService(ActivityApplyRepository repository, DealEventService dealEvents) {
        this.repository = repository;
        this.dealEvents = dealEvents;
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

    /** Сохранение с событием смены статуса в ленте тендера (спека 2026-10-18 §5). */
    @Transactional
    public ActivityApply saveWithStatusEvent(ActivityApply apply, String previousStatus) {
        ActivityApply saved = save(apply);
        if (saved.getTender() != null && saved.getStatus() != null && !saved.getStatus().equals(previousStatus)) {
            dealEvents.stage(saved.getTender().getId(), DealEventService.APPLY_STATUS,
                    "Заявка на участие № " + saved.getId() + ": " + ApplyStatusLabels.label(saved.getStatus()), saved.getId());
        }
        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
