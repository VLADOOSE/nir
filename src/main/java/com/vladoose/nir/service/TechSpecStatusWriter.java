package com.vladoose.nir.service;

import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Запись исхода фонового разбора ТЗ. ОТДЕЛЬНЫЙ бин с @Transactional: сеть (скачивание PDF) живёт
 * вне транзакции, а работа с БД из фонового потока должна идти через вызов другого бина —
 * при self-invoke прокси не отработает и рыночный аспект не получит привязанную сессию (§6).
 *
 * <p>Постановки в очередь здесь нет: новый лот получает PENDING прямо в импорт-райтере (Task 6),
 * исторические — миграцией V16.
 */
@Service
public class TechSpecStatusWriter {

    private final TenderLotRepository lotRepository;

    public TechSpecStatusWriter(TenderLotRepository lotRepository) {
        this.lotRepository = lotRepository;
    }

    /**
     * Отметить попытку. Успешный разбор уже проставил OK в {@link TechSpecWriter}, но отметку
     * времени ставим и здесь: она отвечает на вопрос «когда очередь трогала лот в последний раз».
     */
    @Transactional
    public void markResult(Long lotId, TechSpecStatus status) {
        lotRepository.findById(lotId).ifPresent(lot -> {
            lot.setTechSpecStatus(status);
            lot.setTechSpecAttemptedAt(OffsetDateTime.now());
            lotRepository.save(lot);
        });
    }
}
