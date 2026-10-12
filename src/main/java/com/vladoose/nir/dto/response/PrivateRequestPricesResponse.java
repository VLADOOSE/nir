package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Таблица цен заявки (спека §7): позиции × поставщики, кому писали; лучшая цена по позиции. */
public record PrivateRequestPricesResponse(List<Supplier> suppliers, List<Line> lines) {

    /** clientDisclosed — хоть в одном запросе этому поставщику указан клиент (👤 в шапке колонки). */
    public record Supplier(Long distributorId, String name, OffsetDateTime firstSentAt, boolean clientDisclosed) {}

    public record Line(Long lotId, String name, Integer quantity, String unit, List<Cell> cells,
                       BigDecimal bestPrice, String bestDistributorName) {}

    /** state: PRICE | DECLINED | BOUNCED | NOT_SENT | WAITING | NONE (позицию у поставщика не запрашивали). */
    public record Cell(Long distributorId, String state, BigDecimal price, String note, Long priceRequestId,
                       Long itemId, boolean best) {}
}
