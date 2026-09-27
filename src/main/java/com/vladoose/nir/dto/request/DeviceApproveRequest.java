package com.vladoose.nir.dto.request;

import lombok.Data;

/** «Допустить»: подпись устройства; пусто — берётся имя из запроса. */
@Data
public class DeviceApproveRequest {
    private String label;
}
