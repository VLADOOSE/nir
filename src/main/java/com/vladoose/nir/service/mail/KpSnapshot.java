package com.vladoose.nir.service.mail;

import java.math.BigDecimal;
import java.util.List;

/**
 * Запрос КП для текста уведомления — без JPA: тексты собирает чистая функция. price — цена единственного лота или null;
 * emailOverride — письмо ушло на адрес, вписанный в окне запроса цен, а не на почту карточки (возврат лечится не правкой
 * карточки, а новым запросом: «Переслать» повторит тот же адрес).
 */
public record KpSnapshot(long id, String supplierName, String supplierEmail, long tenderId, String tenderNumber,
                         boolean privateRequest, List<LotLine> lots, String status, BigDecimal price,
                         boolean emailOverride) {

    public record LotLine(String name, Integer quantity) {}
}
