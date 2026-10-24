package com.vladoose.nir.service.tenderresults.protocol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Протокол открытого конкурса (HTML): перечень лотов (количество), раздел лота «№ лота …», таблицы «заявки», «отклонённые»,
 * «расчёт условных цен» («Цена поставщика» — за единицу без НДС: сверено 2026-10-24 с договорами площадки, 62 лота из 62, 60 — больше 1 шт.;
 * сумма заявки — цена × количество), «Определить победителем по лоту …», «занявший второе место», «заключить договор о
 * государственных закупках с …»; провал — «Признать … по лоту № … несостоявшейся в связи с …». Строки таблиц — ячейки через « | ».
 * Победитель — участник, с кем «заключить договор» (у лота с одной заявкой таблицы победителя нет вовсе); БИН из таблицы победителя
 * — только если это участник лота: в новой вёрстке под «Определить победителем» стоит бенефициарный владелец с его ИИН.
 */
final class KonkursProtocolParser {

    private static final Pattern LOT = Pattern.compile("^№ лота\\s+(\\S+)");
    private static final Pattern WINNER_CAPTION = Pattern.compile("победителем по лоту №\\s*\\S+?\\s*:?$");
    private static final Pattern FAILED_LOT = Pattern.compile("по лоту №\\s*(\\S+)\\s+несостоявшейся");
    private static final Pattern LOT_CODE = Pattern.compile("\\d+-[А-ЯA-Z]+\\d+");
    private static final Pattern CONTRACT_WITH = Pattern.compile("заключить договор о государственных закупках с\\s+(.+?)\\.?\\s*$");

    private enum Mode { NONE, APPS, REJECTED, PRICES, WINNER, SECOND }

    private KonkursProtocolParser() {}

    static Map<String, LotProtocol> parse(List<String> ru) {
        Map<String, BigDecimal> qty = quantities(ru);
        Map<String, ProtocolBuilder> lots = new LinkedHashMap<>();
        Map<String, List<ProtocolBid>> apps = new LinkedHashMap<>();
        Map<String, String> contractWith = new HashMap<>();
        ProtocolBuilder cur = null;
        Mode mode = Mode.NONE;
        for (String line : ru) {
            Matcher lot = LOT.matcher(line);
            if (lot.find()) {
                cur = lots.computeIfAbsent(lot.group(1), ProtocolBuilder::new);
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
            if (!line.contains(" | ")) {
                Matcher with = CONTRACT_WITH.matcher(line);
                if (with.find()) {
                    contractWith.putIfAbsent(cur.code, with.group(1));
                    mode = Mode.NONE;
                } else if (line.startsWith("Информация о представленных заявках")) { cur.bidsCount = ProtocolNums.count(line); mode = Mode.APPS; }
                else if (line.startsWith("Отклоненные заявки")) { cur.rejectedCount = ProtocolNums.count(line); mode = Mode.REJECTED; }
                else if (line.startsWith("Расчет условных цен")) mode = Mode.PRICES;
                else if (WINNER_CAPTION.matcher(line).find()) mode = Mode.WINNER;
                else if (line.contains("занявший второе место")) mode = Mode.SECOND;
                else if (!line.startsWith("Решение конкурсной")) mode = Mode.NONE;
                continue;
            }
            String[] c = line.split(" \\| ", -1);
            if (c.length < 3 || !c[0].trim().matches("\\d+") || !ProtocolNums.isBin(c[2])) continue;
            String name = c[1].trim();
            String bin = c[2].trim();
            switch (mode) {
                case APPS -> apps.computeIfAbsent(cur.code, k -> new ArrayList<>())
                        .add(new ProtocolBid(null, name, bin, null, null, ProtocolNums.dateTime(c[c.length - 1]), false, null));
                case REJECTED -> cur.bids.add(new ProtocolBid(null, name, bin, null, null, null, true, c.length > 3 ? c[3].trim() : null));
                case PRICES -> {
                    BigDecimal unit = c.length > 4 ? ProtocolNums.money(c[4]) : null;
                    BigDecimal q = qty.get(cur.code);
                    BigDecimal total = unit == null || q == null || q.signum() <= 0 ? null : unit.multiply(q).setScale(2, RoundingMode.HALF_UP);
                    cur.bids.add(new ProtocolBid(Integer.parseInt(c[0].trim()), name, bin, unit, total,
                            ProtocolNums.dateTime(c[c.length - 1]), false, null));
                }
                case WINNER -> { if (cur.winnerBin == null) cur.winnerBin = bin; }
                case SECOND -> { if (cur.secondBin == null) cur.secondBin = bin; }
                default -> { }
            }
        }
        // заявки есть, а расчёта цен нет (лот сорвался после рассмотрения) — участники без цен
        apps.forEach((code, list) -> {
            ProtocolBuilder b = lots.get(code);
            if (b != null && b.bids.stream().allMatch(ProtocolBid::rejected)) b.bids.addAll(0, list);
        });
        lots.values().forEach(b -> participantsOnly(b, contractWith.get(b.code)));
        Map<String, LotProtocol> out = new LinkedHashMap<>();
        lots.forEach((code, b) -> out.put(code, b.build()));
        return out;
    }

    /**
     * Победитель — участник лота, с кем «заключить договор» (название без учёта регистра и вида кавычек); нет такого — БИН из таблицы
     * победителя, если это участник. Чужой номер (ИИН бенефициара) не берётся — лучше без победителя, чем с чужим человеком.
     */
    private static void participantsOnly(ProtocolBuilder b, String contractName) {
        Map<String, String> byName = new HashMap<>();
        Set<String> bins = new HashSet<>();
        for (ProtocolBid bid : b.bids) {
            if (bid.rejected() || bid.bin() == null) continue;
            bins.add(bid.bin());
            if (bid.name() != null) byName.putIfAbsent(norm(bid.name()), bid.bin());
        }
        String named = contractName == null ? null : byName.get(norm(contractName));
        b.winnerBin = named != null ? named : bins.contains(b.winnerBin) ? b.winnerBin : null;
    }

    /** «ИП «Поставщик».» и «ип "поставщик"» — одно название: регистр, вид кавычек, пробелы и точка в конце не различаются. */
    static String norm(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[«»“”„‟]", "\"").replaceAll("\\s+", " ").replaceAll("[\\s.]+$", "").strip();
    }

    /** Перечень лотов в начале протокола: «№ | № лота | Наименование | Количество | …» → количество по номеру лота. */
    static Map<String, BigDecimal> quantities(List<String> ru) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (String line : ru) {
            String[] c = line.split(" \\| ", -1);
            if (c.length >= 4 && c[0].trim().matches("\\d+") && LOT_CODE.matcher(c[1].trim()).matches()) {
                BigDecimal q = ProtocolNums.money(c[3]);
                if (q != null) out.putIfAbsent(c[1].trim(), q);
            }
        }
        return out;
    }
}
