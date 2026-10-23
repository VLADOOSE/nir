package com.vladoose.nir.service.rates;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.CronSchedules;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesClient;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Курс НБ РК (спека §4.2): при старте и по расписанию RATES_NBRK_CRON (умолчание — раз в 3 часа в :07, Уральск), свой поток
 * `rates-nbrk`. Выключатель RATES_NBRK_ENABLED (по умолчанию выключено). Сбой — WARN, прежние курсы остаются.
 */
@Component
public class RatesScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RatesScheduler.class);
    static final String DEFAULT_CRON = "0 7 */3 * * *";
    static final ZoneId ZONE = ZoneId.of("Asia/Oral");

    private final NbrkRatesClient client;
    private final RatesWriter writer;
    private final boolean enabled;
    private final String cron;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rates-nbrk");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public RatesScheduler(NbrkRatesClient client, RatesWriter writer, @Value("${rates.nbrk.enabled:false}") boolean enabled,
                          @Value("${rates.nbrk.cron:}") String cron) {
        this(client, writer, enabled, cron, Clock.systemUTC());
    }

    public RatesScheduler(NbrkRatesClient client, RatesWriter writer, boolean enabled, String cron, Clock clock) {
        this.client = client;
        this.writer = writer;
        this.enabled = enabled;
        this.cron = cron;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (enabled) executor.execute(this::runSafe);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        CronSchedules.register(registrar, "Курс НБ РК", cron, DEFAULT_CRON, ZONE, () -> executor.execute(this::runSafe), log);
    }

    /** Один проход: курс на сегодня по Уральску. Ошибка — исключением (её ловит runSafe). */
    public int run() {
        LocalDate today = LocalDate.now(clock.withZone(Market.KZ.zone()));
        List<NbrkRate> rates = client.fetch(today);
        int n = writer.store(today, rates, OffsetDateTime.now(clock));
        log.info("Курс НБ РК на {}: {}", today, rates.stream().filter(r -> RatesWriter.WANTED.contains(r.currency()))
                .map(r -> r.currency() + " " + RateMath.text(r.rate())).collect(Collectors.joining(", ")));
        return n;
    }

    public void runSafe() {
        try {
            run();
        } catch (Throwable e) {
            log.warn("Курс НБ РК не получен, остаётся прежний: {}", ErrorText.of(e));
        }
    }
}
