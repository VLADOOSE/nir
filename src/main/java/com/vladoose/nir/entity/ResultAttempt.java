package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Попытка той же закупки (пункт плана площадки): номер объявления и лота, способ («ЗЦП», «ОИ», «ОК»), статус лота,
 * время публикации и итогов (время площадки строкой), поставщик закупки из одного источника, тендер в АИС.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultAttempt(String anno, String lotNumber, String method, Integer statusId, String status, String publishedAt,
                            String resultsAt, String supplierBin, Long tenderId) {}
