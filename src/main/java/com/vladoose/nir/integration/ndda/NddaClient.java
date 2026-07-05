package com.vladoose.nir.integration.ndda;

import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;

/** Реестр МИ НЦЭЛС РК (oldregister.ndda.kz) — публичный API без auth. */
public interface NddaClient {

    /** Внутренний id портала по точному № РУ (list-фильтр); null — на портале не найден. */
    Long resolveId(String regNumber);

    /** Карточка РУ по внутреннему id (описание: назначение/область/класс риска/характеристики/вид МИ). */
    NddaDetailDto fetchDetail(long id);
}
