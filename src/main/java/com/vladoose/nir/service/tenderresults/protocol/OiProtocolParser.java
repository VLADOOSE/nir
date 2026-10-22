package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Протокол закупки из одного источника по несостоявшимся (HTML; сорвавшиеся — PDF): «№ лота: …», приглашённый поставщик
 * (название и БИН), его цена («выделенная сумма, цена поставщика, время»), решение — «Решено заключить договор…» или
 * «…не состоялась в связи с тем, что … отказался…».
 */
final class OiProtocolParser {

    private static final Pattern LOT = Pattern.compile("№ лота:\\s*(\\S+)");
    private static final Pattern BIN = Pattern.compile("(\\d{12})");
    private static final Pattern PRICE = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*\\|?\\s*(\\d+(?:\\.\\d+)?)\\s*\\|?\\s*"
            + "(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");

    private OiProtocolParser() {}

    static Map<String, LotProtocol> parse(List<String> ru) {
        String code = null, name = null, bin = null, conclusion = null, section = "";
        BigDecimal price = null;
        LocalDateTime at = null;
        boolean done = false;
        FailReason fail = null;
        for (String line : ru) {
            Matcher lot = LOT.matcher(line);
            if (code == null && lot.find()) {
                code = lot.group(1);
                continue;
            }
            if (line.startsWith("Информация о приглашенном")) { section = "SUPPLIER"; continue; }
            if (line.startsWith("Информация о ценовом предложении")) { section = "PRICE"; continue; }
            if (line.startsWith("Результаты соответствия")) { section = ""; continue; }
            if (line.contains("Решено заключить договор")) {
                done = true;
                conclusion = line;
                continue;
            }
            if (line.contains("не состоялась")) {
                fail = ProtocolNums.reason(line);
                conclusion = line;
                continue;
            }
            if (section.equals("SUPPLIER") && bin == null) {
                Matcher m = BIN.matcher(line);
                if (m.find()) {
                    bin = m.group(1);
                    name = line.substring(0, m.start()).replace("|", "").trim();
                }
            } else if (section.equals("PRICE") && price == null) {
                Matcher m = PRICE.matcher(line);
                if (m.find()) {
                    price = ProtocolNums.money(m.group(2));
                    at = ProtocolNums.dateTime(m.group(3));
                }
            }
        }
        if (code == null) return Map.of();
        ProtocolBuilder b = new ProtocolBuilder(code);
        if (bin != null) b.bids.add(new ProtocolBid(price == null ? null : 1, name, bin, null, price, at, false, null));
        b.bidsCount = price == null ? 0 : 1;
        b.rejectedCount = 0;
        if (done && bin != null) b.winnerBin = bin;
        else b.failReason = fail != null ? fail : FailReason.OTHER;
        b.conclusion = conclusion;
        return Map.of(code, b.build());
    }
}
