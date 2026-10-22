package com.vladoose.nir.service.tenderresults.protocol;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Разбор «Протокола итогов» goszakup (спека 2026-10-22-tender-results-market-prices §5). Вид — по первой строке русской части;
 * незнакомый вид, пустой разбор, нечитаемый файл — UNKNOWN. Сбой внутри разбора — исключение: проход его ловит и помечает
 * протокол «не разобран».
 */
public final class ProtocolParser {

    private static final Pattern ZCP_HEAD = Pattern.compile("Протокол об итогах \\d+-ЗЦП\\d*");

    private ProtocolParser() {}

    public static ProtocolResult parse(byte[] bytes) {
        List<String> lines = ProtocolText.lines(bytes);
        return lines == null ? ProtocolResult.unknown() : parseLines(lines);
    }

    public static ProtocolResult parseLines(List<String> lines) {
        List<String> ru = ProtocolText.russian(lines);
        if (ru.isEmpty()) return ProtocolResult.unknown();
        String head = ru.get(0);
        ProtocolFormat format;
        Map<String, LotProtocol> lots;
        if (head.contains("способом конкурса")) {
            format = ProtocolFormat.KONKURS;
            lots = KonkursProtocolParser.parse(ru);
        } else if (head.contains("из одного источника")) {
            format = ProtocolFormat.OI;
            lots = OiProtocolParser.parse(ru);
        } else if (ZCP_HEAD.matcher(head).find()) {
            format = ProtocolFormat.ZCP;
            lots = ZcpProtocolParser.parse(ru);
        } else {
            return ProtocolResult.unknown();
        }
        return lots.isEmpty() ? ProtocolResult.unknown() : new ProtocolResult(format, lots);
    }
}
