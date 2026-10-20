package com.vladoose.nir.dto.request;

import lombok.Data;

/** Закрепить модель в работе: ровно одно из двух — наш товар сайта или № РУ (спека 2026-10-20-lot-model-in-work §5.5). */
@Data
public class LotModelRequest {
    private Long siteProductId;
    private String regNumber;
    private String note;
}
