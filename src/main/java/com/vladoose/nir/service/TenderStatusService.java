package com.vladoose.nir.service;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Локальное авто-завершение: ACTIVE с прошедшим дедлайном → COMPLETED.
 * Работает в рынке из MarketContext (ставит вызывающий, §6); следующий импорт
 * с площадки сверяет и при продлении дедлайна вернёт статус обратно.
 */
@Service
public class TenderStatusService {

    private final TenderRepository tenderRepository;

    public TenderStatusService(TenderRepository tenderRepository) {
        this.tenderRepository = tenderRepository;
    }

    @Transactional
    public int completeExpired(LocalDate today) {
        // через выборку (не bulk-JPQL): Hibernate-фильтр рынка применяется только к запросам сессии
        List<Tender> expired = tenderRepository.findByStatusAndDeadlineBefore("ACTIVE", today);
        for (Tender t : expired) {
            t.setStatus("COMPLETED");
        }
        return expired.size();
    }
}
