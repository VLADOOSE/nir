package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.integration.telegram.TelegramText;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Блок «Для АИС» из ответа ИИ (спека 2026-10-21-tender-telegram-bot §6): строки «L5826 | идти | P123 | почему» где угодно
 * в тексте — в блоке кода, таблице Markdown, списке, с кодами в скобках и кириллическими «Л» / «Р»; рассуждение вокруг не
 * мешает. Повтор лота — последняя строка. Строка с кодом лота, которую не разобрать, — в unparsed. Чистая функция.
 */
public final class AiAnswerParser {

    public static final int REASON_MAX = 500;

    public record Line(long lotId, AiVerdict verdict, Long productId, String reason) {}

    public record Parsed(List<Line> lines, List<String> unparsed) {}

    private static final Pattern LOT = Pattern.compile("^\\[?\\s*[LlЛл]\\s*(\\d{1,12})\\s*]?$");
    private static final Pattern PRODUCT = Pattern.compile("^\\[?\\s*[PpРр]\\s*(\\d{1,12})\\s*]?$");
    private static final Pattern LOOKS_LIKE_LINE = Pattern.compile("(?<![\\p{L}\\d])\\[?[LlЛл]\\s*\\d{1,12}]?\\s*\\|");
    private static final Pattern LEAD = Pattern.compile("^(?:[>*•\\-–—]+\\s*|\\d{1,3}[.)]\\s+)+");
    private static final Set<String> NO_PRODUCT = Set.of("", "—", "-", "–", "нет", "n/a", "none", "null");

    private AiAnswerParser() {}

    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) return new Parsed(List.of(), List.of());
        Map<Long, Line> lines = new LinkedHashMap<>();
        List<String> unparsed = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String s = LEAD.matcher(raw.replace("`", "").strip()).replaceFirst("").strip();
            if (s.startsWith("|")) s = s.substring(1);
            if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
            String[] cells = s.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) cells[i] = cells[i].strip();
            var lot = LOT.matcher(cells[0]);
            if (cells.length < 3 || !lot.matches()) {
                if (LOOKS_LIKE_LINE.matcher(s).find()) unparsed.add(TelegramText.oneLine(raw, 120));
                continue;
            }
            AiVerdict verdict = verdict(cells[1]);
            if (verdict == null) {
                unparsed.add(TelegramText.oneLine(raw, 120));
                continue;
            }
            Long product = null;
            String productText = null;
            var p = PRODUCT.matcher(cells[2]);
            if (p.matches()) product = Long.parseLong(p.group(1));
            else if (!NO_PRODUCT.contains(cells[2].toLowerCase(Locale.ROOT))) productText = cells[2];
            String why = cells.length > 3 ? String.join(" | ", Arrays.copyOfRange(cells, 3, cells.length)).strip() : "";
            String reason = productText == null ? why : productText + (why.isEmpty() ? "" : "; " + why);
            long lotId = Long.parseLong(lot.group(1));
            lines.remove(lotId);
            lines.put(lotId, new Line(lotId, verdict, product, reason.isEmpty() ? null : TelegramText.oneLine(reason, REASON_MAX)));
        }
        return new Parsed(List.copyOf(lines.values()), List.copyOf(unparsed));
    }

    static AiVerdict verdict(String cell) {
        String v = cell.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[*_«»\"'.!:;]", "").replaceAll("\\s+", " ").strip();
        if (v.startsWith("не наш") || v.equals("нет") || v.startsWith("не иди") || v.startsWith("не подход") || v.equals("no")) {
            return AiVerdict.NO;
        }
        if (v.startsWith("не ясн") || v.equals("неясно") || v.equals("?") || v.startsWith("не уверен") || v.equals("unsure")) {
            return AiVerdict.UNSURE;
        }
        if (v.startsWith("идти") || v.equals("иди") || v.equals("да") || v.startsWith("подход") || v.equals("go") || v.equals("yes")) {
            return AiVerdict.GO;
        }
        return null;
    }
}
