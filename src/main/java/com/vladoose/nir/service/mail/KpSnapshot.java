package com.vladoose.nir.service.mail;

import java.math.BigDecimal;
import java.util.List;

/** Запрос КП для текста уведомления — без JPA: тексты собирает чистая функция. price — цена единственного лота или null. */
public record KpSnapshot(long id, String supplierName, String supplierEmail, long tenderId, String tenderNumber,
                         boolean privateRequest, List<LotLine> lots, String status, BigDecimal price) {

    public record LotLine(String name, Integer quantity) {}
}
