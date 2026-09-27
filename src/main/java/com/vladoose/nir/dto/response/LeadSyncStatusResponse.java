package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Плашка «westmed.kz · синхронизировано N мин назад» на странице «Обращения». */
@Data
public class LeadSyncStatusResponse {
    private boolean enabled;
    private boolean writeStatus;
    private boolean running;
    private OffsetDateTime lastRunAt;
    private OffsetDateTime lastSuccessAt;
    private String lastError;
    private int lastCreated;
}
