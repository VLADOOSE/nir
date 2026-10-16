package com.vladoose.nir.dto.response;

/**
 * Ответ на курс файла: вид экрана и итог — сколько цен подставлено (applied; в том числе сверенных и выбранных, пошедших
 * за курсом), снято (removed — курс снят), ручных не тронуто (keptManual); closed — запрос закрыт: курс сохранён, цены
 * не трогали. Экран собирает из итога тост, а не пишет «пересчитано» наугад.
 */
public record SupplierReplyRateResponse(SupplierReplyView view, int applied, int removed, int keptManual, boolean closed) {}
