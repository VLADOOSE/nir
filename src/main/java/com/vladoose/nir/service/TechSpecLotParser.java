package com.vladoose.nir.service;

import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.exception.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Разбор ТЗ одного лота с записью исхода — общий для фоновой очереди ({@link TechSpecBackfillScheduler}) и поиска нашей
 * продукции (спека 2026-10-19-product-watch §7.4). Перенесено из TechSpecBackfillScheduler.processOne без изменений:
 * отметка ДО разбора (exit 137 не ловится никаким catch), исход записывается всегда, Error отдаётся наружу после записи.
 */
@Component
public class TechSpecLotParser implements LotSpecParser {

    private static final Logger log = LoggerFactory.getLogger(TechSpecLotParser.class);

    /** outage — лот не разобрался именно из-за недоступности площадки (для предохранителя). */
    public record Result(TechSpecStatus status, boolean outage) {}

    private final TechSpecService techSpecService;
    private final TechSpecStatusWriter writer;

    public TechSpecLotParser(TechSpecService techSpecService, TechSpecStatusWriter writer) {
        this.techSpecService = techSpecService;
        this.writer = writer;
    }

    /** Один лот. Исход записывается ВСЕГДА — иначе очередь качала бы один и тот же PDF вечно. */
    @Override
    public Result parse(Long lotId) {
        // ⚠ Отметка ДО разбора, а не только после. catch(Error) спасает от OutOfMemoryError
        // ВНУТРИ JVM, но наблюдавшаяся на этом проекте форма — exit 137, SIGKILL от OOM-киллера
        // контейнера (прод: -Xmx1g под mem_limit 1300m, §5/§16). Тогда не выполняется НИ ОДНА
        // строка Java: без предварительной отметки лот остался бы PENDING, после рестарта был бы
        // выбран первым же (запрос детерминированный, ORDER BY id DESC) и убивал бы контейнер
        // каждый интервал. С отметкой он выпадает из очереди и вернётся сам — тем же
        // requeueStaleErrors, что и лоты, сгоревшие на аварии площадки (нового состояния не надо).
        writer.markResult(lotId, TechSpecStatus.ERROR);

        TechSpecStatus status;
        boolean outage = false;
        Error fatal = null;
        try {
            techSpecService.parse(lotId);      // сеть + PDF, ВНЕ транзакции
            status = TechSpecStatus.OK;
        } catch (NotFoundException e) {
            status = TechSpecStatus.NO_FILE;
        } catch (UnprocessableException e) {
            status = TechSpecStatus.UNREADABLE;
        } catch (BadRequestException e) {
            // Лот принципиально неразбираем: SK-лот без кода площадки, тендер без sourceExtId.
            // Случай «нет токена» сюда не доходит — такие лоты в пачку не попадают (nextBatch).
            status = TechSpecStatus.NO_FILE;
        } catch (UpstreamException e) {
            status = TechSpecStatus.ERROR;
            outage = true;
        } catch (RuntimeException e) {
            log.warn("Разбор ТЗ лота {} упал неожиданно: {}", lotId, e.toString());
            status = TechSpecStatus.ERROR;
        } catch (Error e) {
            // ⚠ Не «на всякий случай»: OutOfMemoryError на этом самом пути (PDFBox грузит PDF в
            // память) — наблюдавшееся состояние, §5/§16, прод живёт на -Xmx1g. Без этой ветки
            // Error пролетал бы мимо markResult, лот остался бы PENDING, и следующий тик выбрал
            // бы ТОТ ЖЕ лот (запрос детерминированный) — очередь встала бы навсегда на одном PDF.
            log.error("Разбор ТЗ лота {} упал фатально: {}", lotId, e.toString());
            status = TechSpecStatus.ERROR;
            fatal = e;
        }
        // Запись исхода не должна подменить собой уже случившийся Error: JPA save аллоцирует, то
        // есть под настоящим исчерпанием кучи падает именно она — и наружу ушла бы ошибка записи
        // вместо исходного OutOfMemoryError, а лот остался бы с предварительной отметкой.
        try {
            writer.markResult(lotId, status);  // отдельный бин → есть привязанная сессия
        } catch (Throwable writeFailure) {
            if (fatal == null) throw writeFailure;
            fatal.addSuppressed(writeFailure);
        }
        // Исход записан — теперь можно отдать Error наружу: глушить обработку JVM мы не вправе.
        if (fatal != null) throw fatal;
        return new Result(status, outage);
    }
}
