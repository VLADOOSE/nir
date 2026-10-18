package com.vladoose.nir.dto.response;

/**
 * Ответ на курс или валюту файла: вид экрана и итог — сколько цен подставлено (applied; в том числе сверенных и
 * выбранных, пошедших за курсом), снято (removed — курс снят или у позиции не стало цены после смены валюты), ручных не
 * тронуто (keptManual); closed — запрос закрыт: курс или валюта сохранены, цены не трогали. Экран собирает из итога
 * тост, а не пишет «пересчитано» наугад.
 */
public record SupplierReplyRateResponse(SupplierReplyView view, int applied, int removed, int keptManual, boolean closed) {}
