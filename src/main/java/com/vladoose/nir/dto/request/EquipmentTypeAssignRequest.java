package com.vladoose.nir.dto.request;

import lombok.Data;

@Data
public class EquipmentTypeAssignRequest {
    /** id вида МИ; null — снять тип с лота. */
    private Long typeId;
}
