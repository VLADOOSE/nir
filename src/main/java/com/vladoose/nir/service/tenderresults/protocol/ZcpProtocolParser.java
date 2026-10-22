package com.vladoose.nir.service.tenderresults.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Протокол запроса ценовых предложений (PDF): раздел лота — «Лот № …»; отклонённые порталом и ценовые предложения —
 * «<№> <название> <БИН> <цена за ед.> <сумма по ст. 13> <общая сумма> <дата> <время>» (название бывает перенесено);
 * вывод — «Определить победителем по лоту № … : <БИН> …, … второе место <БИН> …» или «Признать … несостоявшейся в связи с …».
 */
final class ZcpProtocolParser {

    private static final Pattern LOT = Pattern.compile("^Лот №\\s*(\\S+)$");
    private static final Pattern ROW = Pattern.compile("^(\\d+) (.+?) (\\d{12}) (\\d+(?:\\.\\d+)?) (\\d+(?:\\.\\d+)?) (\\d+(?:\\.\\d+)?) "
            + "(\\d{2}\\.\\d{2}\\.\\d{4}) (\\d{2}:\\d{2}:\\d{2})$");
    private static final Pattern REJECTED_ROW = Pattern.compile("^(\\d+) (.+?) (\\d{12}) (.+)$");
    private static final Pattern WINNER = Pattern.compile("победителем по лоту №\\s*(\\S+?)\\s*:\\s*(\\d{12})");
    private static final Pattern SECOND = Pattern.compile("второе место\\s+(\\d{12})");
    private static final Pattern FAILED_LOT = Pattern.compile("по лоту №\\s*(\\S+)\\s+несостоявшейся");

    private enum Mode { NONE, REJECTED, BIDS }

    private ZcpProtocolParser() {}

    static Map<String, LotProtocol> parse(List<String> ru) {
        Map<String, ProtocolBuilder> lots = new LinkedHashMap<>();
        ProtocolBuilder cur = null;
        Mode mode = Mode.NONE;
        for (int i = 0; i < ru.size(); i++) {
            String line = ru.get(i);
            Matcher lot = LOT.matcher(line);
            if (lot.matches()) {
                cur = lots.computeIfAbsent(lot.group(1), ProtocolBuilder::new);
                mode = Mode.NONE;
                continue;
            }
            Matcher w = WINNER.matcher(line);
            if (w.find()) {
                ProtocolBuilder b = lots.computeIfAbsent(w.group(1), ProtocolBuilder::new);
                b.winnerBin = w.group(2);
                Matcher s = SECOND.matcher(line);
                if (s.find()) b.secondBin = s.group(1);
                b.conclusion = line;
                mode = Mode.NONE;
                continue;
            }
            if (line.startsWith("Признать")) {
                Matcher f = FAILED_LOT.matcher(line);
                ProtocolBuilder b = f.find() ? lots.computeIfAbsent(f.group(1), ProtocolBuilder::new) : cur;
                if (b != null) {
                    b.failReason = ProtocolNums.reason(line);
                    b.conclusion = line;
                }
                mode = Mode.NONE;
                continue;
            }
            if (cur == null) continue;
            if (line.contains("автоматически отклоненные")) {
                cur.rejectedCount = ProtocolNums.count(line);
                mode = Mode.REJECTED;
                continue;
            }
            if (line.contains("представлены следующие ценовые предложения")) {
                cur.bidsCount = ProtocolNums.count(line);
                mode = Mode.BIDS;
                continue;
            }
            if (mode == Mode.BIDS) {
                String joined = line;
                for (int k = 0; k <= 2 && i + k < ru.size(); k++) {   // название бывает перенесено: склеить до двух следующих строк
                    if (k > 0) joined = joined + " " + ru.get(i + k);
                    Matcher r = ROW.matcher(joined);
                    if (r.matches()) {
                        cur.bids.add(new ProtocolBid(Integer.parseInt(r.group(1)), r.group(2).trim(), r.group(3),
                                ProtocolNums.money(r.group(4)), ProtocolNums.money(r.group(6)),
                                ProtocolNums.dateTime(r.group(7) + " " + r.group(8)), false, null));
                        i += k;
                        break;
                    }
                }
            } else if (mode == Mode.REJECTED) {
                Matcher r = REJECTED_ROW.matcher(line);
                if (r.matches()) cur.bids.add(new ProtocolBid(null, r.group(2).trim(), r.group(3), null, null, null, true, r.group(4).trim()));
            }
        }
        Map<String, LotProtocol> out = new LinkedHashMap<>();
        lots.forEach((code, b) -> out.put(code, b.build()));
        return out;
    }
}
