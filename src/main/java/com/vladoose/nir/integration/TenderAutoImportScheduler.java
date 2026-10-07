package com.vladoose.nir.integration;

import com.vladoose.nir.integration.goszakup.GoszakupImportScheduler;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyImportScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Автозапуск импорта тендеров в рабочие часы ({@code tenders.auto-import.*}, выключено по умолчанию —
 * {@code TENDERS_AUTO_IMPORT_ENABLED}). Тендеры ЗКО живут 2–4 дня — о них надо узнавать в день публикации, а не когда
 * кто-то нажмёт «Обновить тендеры»: goszakup — ежечасно в :10, 8–19, пн–сб; СК-Фармация — трижды в день (её тендеры
 * живут 3–15 дней, прогон — ≈ 900 запросов к порталу, а уведомления по ней фильтр ЗКО всё равно отсекает). Пояс —
 * Уральск. Тик только ставит прогон в его собственный поток (startAsync) и сразу возвращается: общий поток
 * планировщика (почта, опрос сайта, калитка) не занят; идущий прогон не дублируется.
 * <p>
 * Расписание регистрируется в коде, а не {@code @Scheduled(cron = "${…}")}: там пустая переменная окружения или опечатка
 * в cron роняли бы старт всего бэкенда — здесь пусто значит «по умолчанию», «-» выключает площадку, неразборчивое
 * выражение выключает её с ошибкой в логе, неразборчивый пояс — Уральск.
 */
@Component
public class TenderAutoImportScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TenderAutoImportScheduler.class);

    static final String DEFAULT_GOSZAKUP_CRON = "0 10 8-19 * * MON-SAT";
    static final String DEFAULT_SK_PHARMACY_CRON = "0 40 8,12,16 * * MON-SAT";
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Oral");

    private final GoszakupImportScheduler goszakup;
    private final SkPharmacyImportScheduler skPharmacy;
    private final boolean enabled;
    private final String goszakupCron;
    private final String skPharmacyCron;
    private final String zone;

    public TenderAutoImportScheduler(GoszakupImportScheduler goszakup, SkPharmacyImportScheduler skPharmacy,
                                     @Value("${tenders.auto-import.enabled:false}") boolean enabled,
                                     @Value("${tenders.auto-import.goszakup-cron:}") String goszakupCron,
                                     @Value("${tenders.auto-import.sk-pharmacy-cron:}") String skPharmacyCron,
                                     @Value("${tenders.auto-import.zone:}") String zone) {
        this.goszakup = goszakup;
        this.skPharmacy = skPharmacy;
        this.enabled = enabled;
        this.goszakupCron = goszakupCron;
        this.skPharmacyCron = skPharmacyCron;
        this.zone = zone;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        ZoneId z = zone(zone);
        register(registrar, "goszakup", goszakupCron, DEFAULT_GOSZAKUP_CRON, z, this::tickGoszakup);
        register(registrar, "СК-Фармация", skPharmacyCron, DEFAULT_SK_PHARMACY_CRON, z, this::tickSkPharmacy);
    }

    void tickGoszakup() {
        log.info("Автоимпорт тендеров: запуск goszakup (идущий прогон не дублируется)");
        goszakup.startAsync(null);   // все мониторимые больницы KZ
    }

    void tickSkPharmacy() {
        log.info("Автоимпорт тендеров: запуск СК-Фармации (идущий прогон не дублируется)");
        skPharmacy.startAsync();
    }

    private static void register(ScheduledTaskRegistrar registrar, String platform, String cron, String byDefault,
                                 ZoneId zone, Runnable tick) {
        String expr = cron == null || cron.isBlank() ? byDefault : cron.trim();
        if ("-".equals(expr)) {
            log.info("Автоимпорт {}: выключен расписанием «-»", platform);
            return;
        }
        if (!CronExpression.isValidExpression(expr)) {
            log.error("Автоимпорт {}: расписание «{}» не разобрано (cron Spring из 6 полей) — автозапуск {} выключен",
                    platform, expr, platform);
            return;
        }
        registrar.addCronTask(new CronTask(tick, new CronTrigger(expr, zone)));
        log.info("Автоимпорт {}: «{}», пояс {}", platform, expr, zone);
    }

    private static ZoneId zone(String s) {
        if (s == null || s.isBlank()) return DEFAULT_ZONE;
        try {
            return ZoneId.of(s.trim());
        } catch (DateTimeException e) {
            log.error("Автоимпорт тендеров: пояс «{}» не разобран — беру {}", s, DEFAULT_ZONE);
            return DEFAULT_ZONE;
        }
    }
}
