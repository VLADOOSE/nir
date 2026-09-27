package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Устройство или запрос доступа в разделе «Устройства» (спека device-gate §7). */
@Data
public class DeviceResponse {
    private Long id;
    private String status;
    private String code;               // «7K4-QM2»
    private String requesterName;
    private String label;
    private String device;             // «iPhone · Safari»
    private String ip;
    private OffsetDateTime requestedAt;
    private OffsetDateTime decidedAt;
    private String decidedBy;
    private OffsetDateTime lastSeenAt;
    private boolean current;           // ключ этого устройства пришёл в cookie запроса — «это устройство»
}
