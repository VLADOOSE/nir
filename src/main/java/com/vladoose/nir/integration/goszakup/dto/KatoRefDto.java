package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Запись справочника /v2/refs/ref_kato: КАТО-код разбит на части ab(2)+cd(2)+ef(2)+hij(3). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KatoRefDto {
    private String ab;
    private String cd;
    private String ef;
    private String hij;

    /** Полный 9-значный КАТО-код (формат поля kato объявления). */
    public String code() {
        return (ab == null ? "" : ab) + (cd == null ? "" : cd)
                + (ef == null ? "" : ef) + (hij == null ? "" : hij);
    }
}
