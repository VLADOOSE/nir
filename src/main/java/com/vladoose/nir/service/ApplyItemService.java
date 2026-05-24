package com.vladoose.nir.service;

import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ApplyItemRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApplyItemService {

    private final ApplyItemRepository repository;
    private final TenderLotRepository tenderLotRepository;

    public ApplyItemService(ApplyItemRepository repository, TenderLotRepository tenderLotRepository) {
        this.repository = repository;
        this.tenderLotRepository = tenderLotRepository;
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
        if (item.getOfferedCost() != null && item.getTenderLot() != null && item.getTenderLot().getId() != null) {
            TenderLot lot = tenderLotRepository.findById(item.getTenderLot().getId())
                    .orElseThrow(() -> new BadRequestException("Лот не найден"));
            if (lot.getMaxCost() != null && item.getOfferedCost().compareTo(lot.getMaxCost()) > 0) {
                throw new BadRequestException(
                        "Предложенная цена (" + item.getOfferedCost() + " ₽) превышает максимальную цену лота (" + lot.getMaxCost() + " ₽)");
            }
        }
        return repository.save(item);
    }

    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
