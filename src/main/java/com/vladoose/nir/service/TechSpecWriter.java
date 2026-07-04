package com.vladoose.nir.service;

import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.util.SpecConstraintExtractor.SpecConstraints;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запись разобранного ТЗ в лот отдельным транзакционным бином (сеть — вне транзакции, §6).
 * Текст пишется всегда; габариты/вес — только найденные (не найденное не затирает ручное/прежнее).
 */
@Service
public class TechSpecWriter {

    private final TenderLotRepository lotRepository;

    public TechSpecWriter(TenderLotRepository lotRepository) {
        this.lotRepository = lotRepository;
    }

    @Transactional
    public TenderLot apply(Long lotId, String text, SpecConstraints c) {
        TenderLot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        lot.setRequiredSpec(text);
        if (c != null) {
            if (c.maxLengthMm() != null) lot.setMaxLengthMm(c.maxLengthMm());
            if (c.maxWidthMm() != null) lot.setMaxWidthMm(c.maxWidthMm());
            if (c.maxHeightMm() != null) lot.setMaxHeightMm(c.maxHeightMm());
            if (c.maxWeightKg() != null) lot.setMaxWeightKg(c.maxWeightKg());
        }
        return lotRepository.save(lot);
    }
}
