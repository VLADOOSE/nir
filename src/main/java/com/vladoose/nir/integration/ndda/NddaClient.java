package com.vladoose.nir.integration.ndda;

import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;

import java.util.List;

/** Реестр МИ НЦЭЛС РК (oldregister.ndda.kz) — публичный API без auth. */
public interface NddaClient {

    /** Внутренний id портала по точному № РУ (list-фильтр); null — на портале не найден. */
    Long resolveId(String regNumber);

    /** Карточка РУ по внутреннему id (описание: назначение/область/класс риска/характеристики/вид МИ). */
    NddaDetailDto fetchDetail(long id);

    /** Комплектность (состав) аппарата по внутреннему id: список компонентов с производителем/страной. */
    List<NddaComplectItemDto> fetchComplectList(long nddaId);
}
