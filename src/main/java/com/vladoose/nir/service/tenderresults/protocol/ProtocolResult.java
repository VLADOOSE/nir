package com.vladoose.nir.service.tenderresults.protocol;

import java.util.Map;

/** Разбор файла: вид и итоги по номеру лота площадки. */
public record ProtocolResult(ProtocolFormat format, Map<String, LotProtocol> lots) {
    public static ProtocolResult unknown() {
        return new ProtocolResult(ProtocolFormat.UNKNOWN, Map.of());
    }
}
