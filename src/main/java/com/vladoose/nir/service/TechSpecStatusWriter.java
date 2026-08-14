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
     * времени ставим и здесь: она отвечает на вопрос «когда очередь трогала лот в последний раз»
     * и по ней же возвращаются в очередь сгоревшие лоты ({@link #requeueStaleErrors}).
     *
     * <p>Исход НЕ понижает уже проставленный OK: пока лот был в работе, оператор мог вписать ТЗ
     * руками (`TenderLotController` ставит OK), и затирать его отметку неудачей фоновой попытки
     * нельзя — гард переимпорта из Task 7 ключуется ровно на `status == OK`, так что понижение
     * отдало бы набранный оператором текст на затирание `description_ru`.
     */
    @Transactional
    public void markResult(Long lotId, TechSpecStatus status) {
        lotRepository.findById(lotId).ifPresent(lot -> {
            if (lot.getTechSpecStatus() == TechSpecStatus.OK && status != TechSpecStatus.OK) {
                return;
            }
            lot.setTechSpecStatus(status);
            lot.setTechSpecAttemptedAt(OffsetDateTime.now());
            lotRepository.save(lot);
        });
    }

    /**
     * Вернуть в очередь лоты, сгоревшие на недоступности площадки давнее {@code before}.
     *
     * <p>Без этого ERROR был бы КОНЕЧНЫМ состоянием: очередь берёт только PENDING, а импорт-райтеры
     * ставят PENDING исключительно новым лотам (Task 7 намеренно сделал статус переживающим
     * переимпорт). На проде, где goszakup блокирует IP, за сутки туда осели бы все ~1294 лота —
     * и снятие блокировки уже ничего бы не изменило, потребовался бы ручной SQL по боевой базе.
     *
     * <p>Отдельным UPDATE, а не условием в запросе воркера: так горячая выборка остаётся на
     * частичном индексе `idx_lot_tech_spec_pending` (Index Scan Backward, без Sort), а возврат
     * виден в логе и в самой БД, а не спрятан в предикате.
     */
    @Transactional
    public int requeueStaleErrors(OffsetDateTime before) {
        return lotRepository.requeueStaleErrors(before);
    }
}
