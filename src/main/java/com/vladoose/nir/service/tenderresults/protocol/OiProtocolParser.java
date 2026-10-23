package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Протокол закупки из одного источника по несостоявшимся (HTML; сорвавшиеся — PDF). Лотов в протоколе бывает несколько: у каждого —
 * свой раздел «№ лота: …» до следующего (до 2026-10-23 разбирался только первый). В разделе — приглашённый поставщик (название и
 * БИН; в PDF название переносится на строки перед БИН), его цена — «сумма поставщика | цена за единицу | время» (вторая колонка —
 * за единицу: сверено с договорами площадки 2026-10-23), решение — «Решено заключить договор…» или «…не состоялась в связи с тем,
 * что … отказался…» (в PDF переносится на следующие строки).
 */
final class OiProtocolParser {

    private static final Pattern LOT = Pattern.compile("№ лота:\\s*(\\S+)");
    private static final Pattern BIN = Pattern.compile("(\\d{12})");
    private static final Pattern PRICE = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*\\|?\\s*(\\d+(?:\\.\\d+)?)\\s*\\|?\\s*"
            + "(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");
    /** Номер страницы PDF между строками («2 / 5»). */
    private static final Pattern PAGE = Pattern.compile("^\\d+ / \\d+$");
    /** Вывод переносится не больше чем на столько строк. */
    private static final int WRAP_LINES = 3;

    private OiProtocolParser() {}

    static Map<String, LotProtocol> parse(List<String> ru) {
        List<Integer> starts = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < ru.size(); i++) {
            Matcher m = LOT.matcher(ru.get(i));
            if (m.find()) {
                starts.add(i);
                codes.add(m.group(1));
            }
        }
        Map<String, LotProtocol> out = new LinkedHashMap<>();
        for (int k = 0; k < starts.size(); k++) {
            int end = k + 1 < starts.size() ? starts.get(k + 1) : ru.size();
            out.putIfAbsent(codes.get(k), lot(codes.get(k), ru.subList(starts.get(k) + 1, end)));
        }
        return out;
    }

    /** Раздел одного лота — строки после «№ лота: …» до следующего лота или конца протокола. */
    private static LotProtocol lot(String code, List<String> lines) {
        List<String> nameParts = new ArrayList<>();
        String name = null, bin = null, conclusion = null, block = "";
        BigDecimal unit = null, total = null;
        LocalDateTime at = null;
        boolean done = false;
        FailReason fail = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("Информация о приглашенном")) { block = "SUPPLIER"; continue; }
            if (line.startsWith("Информация о ценовом предложении")) { block = "PRICE"; continue; }
            if (line.startsWith("Результаты соответствия")) { block = ""; continue; }
            boolean contract = line.contains("Решено заключить договор");
            if (contract || line.contains("не состоялась")) {
                int end = conclusionEnd(lines, i);
                conclusion = String.join(" ", lines.subList(i, end));
                if (contract) done = true;
                else fail = ProtocolNums.reason(conclusion);
                i = end - 1;
                continue;
            }
            if (block.equals("SUPPLIER") && bin == null) {
                if (PAGE.matcher(line).matches()) continue;
                Matcher m = BIN.matcher(line);
                if (m.find()) {
                    bin = m.group(1);
                    nameParts.add(line.substring(0, m.start()));
                    name = name(nameParts);
                } else if (supplierHeader(line)) {
                    nameParts.clear();
                } else {
                    nameParts.add(line);
                }
            } else if (block.equals("PRICE") && unit == null) {
                Matcher m = PRICE.matcher(line);
                if (m.find()) {
                    total = ProtocolNums.money(m.group(1));
                    unit = ProtocolNums.money(m.group(2));
                    at = ProtocolNums.dateTime(m.group(3));
                }
            }
        }
        ProtocolBuilder b = new ProtocolBuilder(code);
        if (bin != null) b.bids.add(new ProtocolBid(unit == null ? null : 1, name, bin, unit, total, at, false, null));
        b.bidsCount = unit == null ? 0 : 1;
        b.rejectedCount = 0;
        if (done && bin != null) b.winnerBin = bin;
        else b.failReason = fail != null ? fail : FailReason.OTHER;
        b.conclusion = conclusion;
        return b.build();
    }

    /** Конец вывода: перенесённые строки — до начала следующего блока, не больше {@link #WRAP_LINES}. */
    private static int conclusionEnd(List<String> lines, int from) {
        int end = from + 1;
        while (end < lines.size() && end - from <= WRAP_LINES && !blockStart(lines.get(end))) end++;
        return end;
    }

    private static boolean blockStart(String line) {
        return line.startsWith("Расшифровка") || line.startsWith("Информация о") || line.startsWith("Результаты соответствия")
                || line.contains("Решено заключить договор") || line.contains("не состоялась") || PAGE.matcher(line).matches();
    }

    /** Шапка таблицы поставщика — в PDF перенесена на несколько строк («… БИН (ИИН) / ИНН /», «УНП», «Реквизиты»). */
    private static boolean supplierHeader(String line) {
        return line.contains("Наименование поставщика") || line.contains("БИН (ИИН)") || line.contains("Реквизиты") || line.equals("УНП");
    }

    private static String name(List<String> parts) {
        String s = String.join(" ", parts).replace("|", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }
}
