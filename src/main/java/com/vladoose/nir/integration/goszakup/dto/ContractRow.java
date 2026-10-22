package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Договор goszakup со строками по лотам. Статусы — справочник ref_contract_status. */
public record ContractRow(Long id, Long buyId, String number, String signDate, Integer statusId, String supplierBin,
                          BigDecimal sumWithVat, List<ContractUnitRow> units) {

    /** «Не заключён», «Отказ от закупки и договора», «Передан. Не заключён», «Недействительный по решению суда». */
    public static final Set<Integer> NOT_CONCLUDED = Set.of(330, 430, 461, 480);

    /** Подписан и не из «не заключён / отказ / недействителен». */
    public boolean concluded() {
        return signDate != null && !signDate.isBlank() && (statusId == null || !NOT_CONCLUDED.contains(statusId));
    }
}
