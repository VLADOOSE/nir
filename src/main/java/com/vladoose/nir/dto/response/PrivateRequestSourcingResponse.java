package com.vladoose.nir.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** Окно «Запросить цены»: кто подходит по позициям и почему (спека §6.1). */
public record PrivateRequestSourcingResponse(List<Supplier> suppliers, List<LineRef> unmatched) {

    /**
     * preselected — отмечен по умолчанию: есть почта и хоть одна позиция по бренду, ещё не запрошенная (у бренда с
     * производителем с почтой — только у производителя). manufacturer / trusted — отметки карточки поставщика.
     */
    public record Supplier(Long distributorId, String name, String email, boolean hasEmail, boolean preselected,
                           boolean manufacturer, boolean trusted, List<Line> lines) {}

    /** reason: MANUFACTURER («производитель»), BRAND («возит <бренд>») или TYPE («по виду: <вид>» — подсказка без отметки). */
    public record Line(Long lotId, String name, Integer quantity, String unit, String reason, String label,
                       boolean preselected, OffsetDateTime requestedAt) {}

    public record LineRef(Long lotId, String name, String brand) {}
}
