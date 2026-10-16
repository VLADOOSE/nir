package com.vladoose.nir.dto.request;

import java.math.BigDecimal;

/** Курс пересчёта цен файла ответа в валюту запроса: 1 валюта файла = rate валюты запроса; null — снять курс. */
public record ReplyRateRequest(BigDecimal rate) {}
