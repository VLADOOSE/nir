package com.vladoose.nir.service;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ApplyItemRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class TenderLotService {

    private final TenderLotRepository repository;
    private final TenderRepository tenderRepository;
    private final ApplyItemRepository applyItemRepository;

    public TenderLotService(TenderLotRepository repository, TenderRepository tenderRepository, ApplyItemRepository applyItemRepository) {
        this.repository = repository;
        this.tenderRepository = tenderRepository;
        this.applyItemRepository = applyItemRepository;
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
        if (lot.getTender() != null && lot.getLotNumber() != null) {
            Long tenderId = lot.getTender().getId();
            Integer lotNum = lot.getLotNumber();
            Long lotId = lot.getId();

            boolean exists = repository.findByTenderId(tenderId).stream()
                    .anyMatch(l -> l.getLotNumber() != null
                            && l.getLotNumber().equals(lotNum)
                            && (lotId == null || !l.getId().equals(lotId)));

            if (exists) {
                throw new BadRequestException("Лот с номером " + lotNum + " уже существует в этом тендере");
            }
        }

        TenderLot saved = repository.save(lot);
        recalculateTenderTotal(saved.getTender().getId());
        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        if (applyItemRepository.existsByTenderLotId(id)) {
            throw new BadRequestException("Невозможно удалить лот: он используется в позициях заявок. Сначала удалите эти позиции.");
        }
        TenderLot lot = findById(id);
        Long tenderId = lot.getTender().getId();
        repository.deleteById(id);
        recalculateTenderTotal(tenderId);
    }

    private void recalculateTenderTotal(Long tenderId) {
        Tender tender = tenderRepository.findById(tenderId).orElseThrow();
        List<TenderLot> lots = repository.findByTenderId(tenderId);
        BigDecimal total = lots.stream()
                .filter(l -> l.getMaxCost() != null && l.getQuantity() != null)
                .map(l -> l.getMaxCost().multiply(BigDecimal.valueOf(l.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        tender.setTotalCost(total);
        tenderRepository.save(tender);
    }
}
