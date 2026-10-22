package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ResultEventKind;
import com.vladoose.nir.integration.telegram.TelegramText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Сообщения итогов в Telegram (спека 2026-10-22-tender-results-market-prices §7): «📞 Звонить» и наша заявка — по теме за проход,
 * сводка дня — тема нашей продукции. Простой текст; поля площадки — одной строкой ({@link TelegramText#oneLine}); сообщение
 * ≤ 3800 знаков, длиннее — частями «(продолжение)». Чистые функции.
 */
public final class TenderResultsComposer {

    static final int MAX_TEXT = 3800;
    private static final Set<ResultEventKind> CALLS = Set.of(ResultEventKind.NO_BIDS, ResultEventKind.FAILED_OTHER, ResultEventKind.OI_FAILED);

    /** Событие с тем, что о нём нужно сказать. budget — цена за единицу лота (бюджет площадки). */
    public record Item(long eventId, long tenderId, ResultEventKind kind, String lotName, Integer qty, String unit, BigDecimal budget,
                       String customer, String region, String phone, String email, String text, List<String> chain, String ours,
                       String number) {}

    public record Chunk(String text, List<Long> eventIds) {}

    private TenderResultsComposer() {}

    public static List<Chunk> urgent(List<Item> items, String publicUrl) {
        String base = base(publicUrl);
        List<Item> calls = items.stream().filter(i -> CALLS.contains(i.kind())).toList();
        List<Item> bids = items.stream().filter(i -> i.kind() == ResultEventKind.OUR_BID).toList();
        String head = calls.isEmpty() ? "🏁 Наша заявка в протоколе: " + bids.size() : "📞 Звонить: не состоялось " + calls.size();
        List<String> blocks = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Item i : calls) {
            blocks.add(callBlock(i, base));
            ids.add(i.eventId());
        }
        for (int k = 0; k < bids.size(); k++) {
            blocks.add((k == 0 && !calls.isEmpty() ? "🏁 Наша заявка в протоколе:\n" : "") + bidBlock(bids.get(k), base));
            ids.add(bids.get(k).eventId());
        }
        return chunks(head, blocks, ids);
    }

    public static List<Chunk> digest(List<Item> items, String publicUrl) {
        String base = base(publicUrl);
        List<String> blocks = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Item i : items) {
            blocks.add(digestLine(i, base));
            ids.add(i.eventId());
        }
        return chunks("📊 Итоги по нашим тендерам: " + items.size(), blocks, ids);
    }

    /** Блоки по порядку; не помещается — следующее сообщение с пометкой «продолжение». */
    static List<Chunk> chunks(String head, List<String> blocks, List<Long> ids) {
        List<Chunk> out = new ArrayList<>();
        if (blocks.isEmpty()) return out;
        String next = head + " (продолжение)";
        StringBuilder cur = new StringBuilder(head);
        List<Long> curIds = new ArrayList<>();
        for (int k = 0; k < blocks.size(); k++) {
            String block = TelegramText.safeCut(blocks.get(k), MAX_TEXT - next.length() - 2);
            if (!curIds.isEmpty() && cur.length() + 2 + block.length() > MAX_TEXT) {
                out.add(new Chunk(cur.toString(), List.copyOf(curIds)));
                cur = new StringBuilder(next);
                curIds.clear();
            }
            cur.append("\n\n").append(block);
            curIds.add(ids.get(k));
        }
        out.add(new Chunk(cur.toString(), List.copyOf(curIds)));
        return out;
    }

    private static String callBlock(Item i, String base) {
        StringBuilder b = new StringBuilder("▸ ").append(one(i.lotName(), 150, "Без названия"));
        String size = i.qty() == null ? "" : i.qty() + " " + i.unit();
        String budget = i.budget() == null ? "" : "бюджет " + ResultTexts.money(i.budget()) + " за " + i.unit();
        String line = join(size, budget);
        if (!line.isEmpty()) b.append(" — ").append(line);
        String who = join(one(i.customer(), 150, ""), one(i.region(), 60, ""));
        if (!who.isEmpty()) b.append('\n').append(who);
        String phone = one(i.phone(), 60, "");
        String contacts = join(phone.isEmpty() ? "" : "☎ " + phone, one(i.email(), 100, ""));
        if (!contacts.isEmpty()) b.append('\n').append(contacts);
        b.append('\n').append(one(i.text(), 400, ""));
        if (i.chain() != null && i.chain().size() >= 2) b.append("\nПопытки: ").append(one(String.join(" → ", i.chain()), 300, ""));
        if (i.ours() != null && !i.ours().isBlank()) b.append("\nНаш товар: ").append(one(i.ours(), 150, ""));
        tail(b, i, base);
        return b.toString();
    }

    private static String bidBlock(Item i, String base) {
        StringBuilder b = new StringBuilder(one(i.text(), 400, ""));
        b.append("\n▸ ").append(one(i.lotName(), 150, "Без названия"));
        String who = one(i.customer(), 150, "");
        if (!who.isEmpty()) b.append(" — ").append(who);
        tail(b, i, base);
        return b.toString();
    }

    private static String digestLine(Item i, String base) {
        StringBuilder b = new StringBuilder(icon(i.kind())).append(' ').append(one(i.lotName(), 120, "Без названия"));
        String who = one(i.customer(), 120, "");
        if (!who.isEmpty()) b.append(" — ").append(who);
        b.append(": ").append(one(i.text(), 300, ""));
        if (base != null) b.append("\n   ").append(base).append("/tenders?openId=").append(i.tenderId()).append("&market=KZ");
        return b.toString();
    }

    static String icon(ResultEventKind k) {
        return switch (k) {
            case DONE -> "✓";
            case ONE_BID -> "↪";
            case CONTRACT, OI_DONE -> "📄";
            case REPEAT -> "🔁";
            case CANCELLED -> "✕";
            default -> "•";
        };
    }

    private static void tail(StringBuilder b, Item i, String base) {
        b.append("\ngoszakup № ").append(one(i.number(), 40, "—"));
        if (base != null) b.append("\nОткрыть в АИС: ").append(base).append("/tenders?openId=").append(i.tenderId()).append("&market=KZ");
    }

    private static String one(String s, int max, String fallback) {
        String t = TelegramText.oneLine(s, max);
        return t.isBlank() ? fallback : t;
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        return b.isEmpty() ? a : a + " · " + b;
    }

    private static String base(String publicUrl) {
        return publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
    }
}
