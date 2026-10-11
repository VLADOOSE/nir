package com.vladoose.nir.service;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Локальное авто-завершение: ACTIVE с прошедшим сроком → COMPLETED. Срок площадки — с временем (B2): тендер
 * с приёмом до 10:25 закрывается в 10:25, а не в полночь; у ручного тендера (только дата) весь день срока
 * приём ещё идёт. Работает в рынке из MarketContext (ставит вызывающий, §6); следующий импорт с площадки
 * сверяет и при продлении срока вернёт статус обратно.
 */
@Service
public class TenderStatusService {

    private final TenderRepository tenderRepository;

    public TenderStatusService(TenderRepository tenderRepository) {
        this.tenderRepository = tenderRepository;
    }

    /** @param now «сейчас» по часам рынка ({@code Market#zone}) — сроки площадок записаны местным временем */
    @Transactional
    public int completeExpired(LocalDateTime now) {
        // через выборку (не bulk-JPQL): Hibernate-фильтр рынка применяется только к запросам сессии
        Map<Long, Tender> expired = new LinkedHashMap<>();
        for (Tender t : tenderRepository.findByStatusAndDeadlineAtLessThanEqual("ACTIVE", now)) {
            expired.put(t.getId(), t);
        }
        for (Tender t : tenderRepository.findByStatusAndDeadlineBefore("ACTIVE", now.toLocalDate())) {
            expired.putIfAbsent(t.getId(), t);
        }
        expired.values().forEach(t -> t.setStatus("COMPLETED"));
        return expired.size();
    }
}
