package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.service.mail.ClientMailSettings;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Расписание приёма писем клиентов info@: проход — на СВОЁМ однопоточном экзекьюторе «mail-clients» (зависший IMAP не
 * держит ни общий scheduling-1, ни приём zakup@), проходы не идут параллельно. Рынок ящика — ЯВНО, в finally —
 * очистка (§6 CLAUDE.md). Ручного запуска нет: «Проверить почту» во «Входящих» — про zakup@ (спека §4).
 */
@Component
public class ClientMailScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClientMailScheduler.class);

    private final ClientMailReceiveService service;
    private final ClientMailSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-clients");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean(false);

    public ClientMailScheduler(ClientMailReceiveService service, ClientMailSettings settings) {
        this.service = service;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${mail.clients.poll-ms:60000}", initialDelayString = "${mail.clients.initial-delay-ms:30000}")
    public void tick() {
        if (!settings.enabled()) return;
        if (!queued.compareAndSet(false, true)) return;          // прошлый проход ещё идёт — тик пропускаем
        // execute, а не submit: Error прохода (OOM на вложении) дойдёт до обработчика потока, а не осядет в Future
        executor.execute(() -> {
            try {
                pass();
            } finally {
                queued.set(false);
            }
        });
    }

    /** Проход в потоке приёма; null — проход упал (в лог — только класс). */
    ClientMailReceiveService.Result pass() {
        MarketContext.set(settings.market());
        try {
            return service.poll();
        } catch (RuntimeException e) {
            log.warn("Проход приёма писем клиентов упал: {}", e.getClass().getSimpleName());
            return null;
        } finally {
            MarketContext.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
