package com.vladoose.nir.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Раз в 5 минут: визиты из памяти — в БД, истёкшие запросы и устройства без визитов 90 дней — в EXPIRED
 * (спека device-gate §8). Задача короткая — общий пул @Scheduled подходит. Рынок не нужен: таблица общая.
 */
@Component
public class DeviceGateScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeviceGateScheduler.class);

    private final DeviceGateService gate;

    public DeviceGateScheduler(DeviceGateService gate) {
        this.gate = gate;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void tick() {
        try {
            gate.expireStale(OffsetDateTime.now());
        } catch (Exception e) {
            log.warn("калитка: фоновая задача не прошла: {}", e.toString());
        }
    }
}
