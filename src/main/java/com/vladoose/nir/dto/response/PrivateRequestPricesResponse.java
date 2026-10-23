package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Таблица цен заявки (спека §7): позиции × поставщики, кому писали; лучшая цена по позиции. */
public record PrivateRequestPricesResponse(List<Supplier> suppliers, List<Line> lines) {

    /**
     * clientDisclosed — хоть в одном запросе этому поставщику указан клиент (👤 в шапке колонки); replyRequestId —
     * последний запрос поставщику с ответом: ссылка «Ответ ›» в шапке колонки (null — ответа нет).
     */
    public record Supplier(Long distributorId, String name, OffsetDateTime firstSentAt, boolean clientDisclosed,
                           boolean manual, Long replyRequestId) {}

    public record Line(Long lotId, String name, Integer quantity, String unit, List<Cell> cells,
                       BigDecimal bestPrice, String bestDistributorName) {}

    /**
     * state: PRICE | DECLINED | BOUNCED | NOT_SENT | NO_PRICE (поставщик ответил, цены по позиции нет) | WAITING |
     * NONE (позицию у поставщика не запрашивали).
     * emailOverride — письмо ушло на адрес, вписанный в окне запроса: у «не доставлено» подсказка — запросить заново.
     * auto — 💡: цена из разбора письма или файла и ещё не сверена. currency / original / rate — цена пришла в чужой
     * валюте (V44): исходная цена и курс; today — она же по курсу НБ РК сегодня (нет курса сегодня — null).
     */
    public record Cell(Long distributorId, String state, BigDecimal price, String note, Long priceRequestId,
                       Long itemId, boolean best, boolean emailOverride, boolean manual, boolean auto,
                       String currency, BigDecimal original, BigDecimal rate, BigDecimal today) {
        public Cell withBest() {
            return new Cell(distributorId, state, price, note, priceRequestId, itemId, true, emailOverride, manual, auto, currency, original, rate, today);
        }

        public Cell withToday(BigDecimal t) {
            return new Cell(distributorId, state, price, note, priceRequestId, itemId, best, emailOverride, manual, auto, currency, original, rate, t);
        }
    }
}
