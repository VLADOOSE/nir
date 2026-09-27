package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Раздел «Устройства»: ожидающие запросы, допущенные, история (последние 50 решённых). */
@Data
public class DeviceListResponse {
    private List<DeviceResponse> pending = new ArrayList<>();
    private List<DeviceResponse> trusted = new ArrayList<>();
    private List<DeviceResponse> history = new ArrayList<>();
}
