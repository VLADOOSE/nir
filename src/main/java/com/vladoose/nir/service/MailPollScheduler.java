package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Расписание приёма почты (спека §3.5). Проход — на СВОЁМ однопоточном экзекьюторе «mail-imap», а не на общем
 * scheduling-1: зависший IMAP не должен держать остальные фоновые задачи. Проходы никогда не идут параллельно —
 * курсор ящика не делится между двумя проходами. Рынок ящика ставится ЯВНО и чистится в finally (§6 CLAUDE.md).
 */
@Component
public class MailPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailPollScheduler.class);

    private final MailReceiveService mailReceiveService;
    private final boolean enabled;
    private final Duration manualWait;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-imap");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean tickQueued = new AtomicBoolean(false);

    @Autowired
    public MailPollScheduler(MailReceiveService mailReceiveService,
                             @Value("${mail.imap.enabled:false}") boolean enabled) {
        this(mailReceiveService, enabled, Duration.ofSeconds(90));
    }

    MailPollScheduler(MailReceiveService mailReceiveService, boolean enabled, Duration manualWait) {
        this.mailReceiveService = mailReceiveService;
        this.enabled = enabled;
        this.manualWait = manualWait;
    }

    @Scheduled(fixedDelayString = "${mail.imap.poll-ms:60000}", initialDelayString = "${mail.imap.initial-delay-ms:20000}")
    public void tick() {
        if (!enabled) return;
        if (!tickQueued.compareAndSet(false, true)) return;     // прошлый проход ещё идёт — тик пропускаем
        // execute, а не submit: Error прохода (OOM на большом вложении) должен дойти до обработчика потока,
        // а не осесть в Future, которую никто не читает (как у TechSpecBackfillScheduler)
        executor.execute(() -> {
            try {
                pass();
            } finally {
                tickQueued.set(false);
            }
        });
    }

    /** «Проверить почту» («Входящие») и «Проверить ответы» (запросы КП): проход в том же потоке, ждём до manualWait. */
    public PollResultResponse run() {
        Future<PollResultResponse> f = executor.submit(this::pass);
        try {
            return f.get(manualWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return message("Проверка почты ещё идёт — обновите страницу через минуту");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return message("Проверка почты прервана");
        } catch (ExecutionException e) {
            // RuntimeException проход ловит сам — сюда доходит только Error
            log.warn("Проход приёма почты упал", e.getCause());
            return message("Ошибка проверки почты: " + e.getCause().getClass().getSimpleName());
        }
    }

    private PollResultResponse pass() {
        MarketContext.set(mailReceiveService.getMailboxMarket());
        try {
            return mailReceiveService.poll();
        } catch (RuntimeException e) {
            log.warn("Проход приёма почты упал", e);
            return message("Ошибка проверки почты: " + e.getClass().getSimpleName());
        } finally {
            MarketContext.clear();
        }
    }

    private PollResultResponse message(String text) {
        PollResultResponse r = new PollResultResponse();
        r.setEnabled(enabled);
        r.setMessage(text);
        return r;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
