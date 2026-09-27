package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Состояние устройства для страницы калитки (спека device-gate §7). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GateStateResponse {
    private String state;              // NONE | PENDING | TRUSTED | REJECTED | REVOKED | EXPIRED
    private String code;               // «7K4-QM2» — только для PENDING
    private OffsetDateTime expiresAt;  // только для PENDING
}
