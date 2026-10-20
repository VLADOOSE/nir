package com.vladoose.nir.integration;

import org.slf4j.Logger;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Расписания в коде, а не {@code @Scheduled(cron = "${…}")} (CLAUDE.md §8 «Автозапуск импорта»): пустая переменная —
 * умолчание, «-» — выключено, опечатка — выключено с ERROR в логе; старт бэкенда это не роняет.
 */
public final class CronSchedules {

    private CronSchedules() {}

    /** true — задача зарегистрирована. */
    public static boolean register(ScheduledTaskRegistrar registrar, String label, String cron, String byDefault, ZoneId zone,
                                   Runnable tick, Logger log) {
        String expr = cron == null || cron.isBlank() ? byDefault : cron.trim();
        if ("-".equals(expr)) {
            log.info("{}: выключено расписанием «-»", label);
            return false;
        }
        if (!CronExpression.isValidExpression(expr)) {
            log.error("{}: расписание «{}» не разобрано (cron Spring из 6 полей) — выключено", label, expr);
            return false;
        }
        registrar.addCronTask(new CronTask(tick, new CronTrigger(expr, zone)));
        log.info("{}: «{}», пояс {}", label, expr, zone);
        return true;
    }

    public static ZoneId zone(String s, ZoneId byDefault, Logger log) {
        if (s == null || s.isBlank()) return byDefault;
        try {
            return ZoneId.of(s.trim());
        } catch (DateTimeException e) {
            log.error("Пояс «{}» не разобран — беру {}", s, byDefault);
            return byDefault;
        }
    }
}
