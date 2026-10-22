package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.telegram.TelegramText;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сообщение-пачка «🔎 Потенциально наших тендеров» (спека 2026-10-21-tender-telegram-bot §4, чистая функция): до 15
 * тендеров в сообщении (столько берёт «Для ИИ») и не длиннее предела Telegram; строка — значок уровня, наши лоты ссылкой
 * на карточку, заказчик, регион; вторая — сумма наших лотов, срок приёма, наша отметка. Поля площадки — одной строкой и
 * экранированы (HTML).
 */
public final class ProductDigestComposer {

    public static final int MAX_TENDERS = ProductAiBrief.MAX_TENDERS;
    static final int MAX_VISIBLE = 3800;
    private static final BigDecimal THOUSAND = new BigDecimal(1000);
    private static final BigDecimal MILLION = new BigDecimal(1_000_000);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public record Chunk(String html, boolean sound, List<Long> tenderIds) {}

    private ProductDigestComposer() {}

    public static List<Chunk> compose(List<ProductWatchComposer.Card> input, String publicUrl) {
        return compose(input, publicUrl, java.util.Map.of());
    }

    /** То же со строкой «рынок: …» у тендера (спека 2026-10-22-tender-results-market-prices §10). */
    public static List<Chunk> compose(List<ProductWatchComposer.Card> input, String publicUrl, java.util.Map<Long, String> market) {
        String base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
        // ★, ✅, ◐, 🏷 (спека §4); сортировка устойчивая — внутри значка остаётся порядок prepare(): срок приёма
        List<ProductWatchComposer.Card> cards = new ArrayList<>(input);
        cards.sort(java.util.Comparator.comparingInt(ProductDigestComposer::rank));
        List<List<ProductWatchComposer.Card>> groups = new ArrayList<>();
        List<ProductWatchComposer.Card> cur = new ArrayList<>();
        int visible = 80;
        for (ProductWatchComposer.Card c : cards) {
            int len = visible(block(c, base, 99, market.get(c.tenderId()))) + 2;
            if (!cur.isEmpty() && (cur.size() >= MAX_TENDERS || visible + len > MAX_VISIBLE)) {
                groups.add(cur);
                cur = new ArrayList<>();
                visible = 80;
            }
            cur.add(c);
            visible += len;
        }
        if (!cur.isEmpty()) groups.add(cur);
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            List<ProductWatchComposer.Card> g = groups.get(i);
            StringBuilder html = new StringBuilder("🔎 Потенциально наших тендеров: ").append(cards.size());
            if (groups.size() > 1) html.append(" (часть ").append(i + 1).append(" из ").append(groups.size()).append(')');
            int n = 1;
            for (ProductWatchComposer.Card c : g) html.append("\n\n").append(block(c, base, n++, market.get(c.tenderId())));
            out.add(new Chunk(html.toString(), g.stream().anyMatch(c -> c.level().alert()),
                    g.stream().map(ProductWatchComposer.Card::tenderId).toList()));
        }
        return out;
    }

    static String block(ProductWatchComposer.Card c, String base, int n) {
        return block(c, base, n, null);
    }

    static String block(ProductWatchComposer.Card c, String base, int n, String marketLine) {
        String names = lotNames(c.lots());
        String name = base == null ? esc(names)
                : "<a href=\"" + esc(base + "/tenders?openId=" + c.tenderId() + "&market=KZ") + "\">" + esc(names) + "</a>";
        String who = join(", ", TelegramText.oneLine(c.customer(), 120), TelegramText.oneLine(c.region(), 60));
        StringBuilder b = new StringBuilder().append(n).append(". ").append(c.upgrade() ? "⬆ Уточнение: " : "")
                .append(icon(c)).append(' ').append(name);
        if (!who.isEmpty()) b.append(" — ").append(esc(who));
        List<String> second = new ArrayList<>();
        if (c.ourSum() != null) second.add("наши лоты " + money(c.ourSum()));
        if (c.deadlineAt() != null) second.add("до " + c.deadlineAt().format(DAY_TIME));
        else if (c.deadline() != null) second.add("до " + c.deadline().format(DAY));
        String mark = mark(c);
        if (!mark.isEmpty()) second.add(mark);
        if (!second.isEmpty()) b.append("\n   ").append(esc(String.join(" · ", second)));
        if (marketLine != null && !marketLine.isBlank()) b.append("\n   ").append(esc(TelegramText.oneLine(marketLine, 200)));
        return b.toString();
    }

    static int rank(ProductWatchComposer.Card c) {
        return switch (icon(c)) {
            case "★" -> 0;
            case "✅" -> 1;
            case "◐" -> 2;
            default -> 3;
        };
    }

    static String icon(ProductWatchComposer.Card c) {
        if (c.level().exact()) return "★";
        if (c.level() == ProductMatchLevel.FITS) return "✅";
        ProductMatchQuery.FitSummary f = c.lots().isEmpty() ? null : c.lots().get(0).fit();
        return f != null && f.verdict() == FitVerdict.PARTIAL && f.met() > 0 ? "◐" : "🏷";
    }

    static String mark(ProductWatchComposer.Card c) {
        if (c.lots().isEmpty()) return "";
        ProductMatchQuery.LotLine l = c.lots().get(0);
        ProductMatchQuery.FitSummary f = l.fit();
        String fitName = f == null || f.productName() == null ? l.word() : ProductMatchQuery.shortName(f.productName());
        String met = f == null || f.total() == 0 ? "" : " · " + f.met() + " из " + f.total();
        return switch (l.level()) {
            case COPIED -> "ТЗ списано с нашего " + ProductMatchQuery.shortName(l.productName() == null ? l.word() : l.productName());
            case NAMED -> "назван наш " + ProductMatchQuery.what(l);
            case FITS -> "подходит наш " + fitName + met;
            case TYPE -> {
                if (f != null && f.verdict() == FitVerdict.PARTIAL && f.met() > 0) yield "похоже: " + fitName + met;
                if (f != null && f.verdict() == FitVerdict.FITS_NO_RU) yield "подходит по ТЗ " + fitName + ", " + ProductMatchQuery.noRu(f.regStatus());
                if (f != null && f.verdict() == FitVerdict.NOT_FIT) yield "✗ не подходит: " + (f.reasonShort() == null ? fitName : f.reasonShort());
                String similar = l.similar() == null || l.similar().isEmpty() ? ""
                        : " · похоже: " + l.similar().stream().limit(2).map(x -> ProductMatchQuery.shortName(x.name())
                                + (x.brand() == null || x.brand().isBlank() ? "" : " (" + x.brand() + ")")).collect(Collectors.joining(", "));
                yield "наш вид: " + l.word() + similar;
            }
        };
    }

    private static String lotNames(List<ProductMatchQuery.LotLine> lots) {
        List<String> names = lots.stream().map(l -> TelegramText.oneLine(l.lotName() == null ? "лот" : l.lotName(), 80)
                + (l.qty() == null || l.qty() <= 1 ? "" : ", " + l.qty() + " шт.")).distinct().toList();
        String head = String.join("; ", names.subList(0, Math.min(2, names.size())));
        return names.size() > 2 ? head + " и ещё " + (names.size() - 2) : head;
    }

    /** «12,5 млн ₸», «850 тыс. ₸», «950 ₸». */
    static String money(BigDecimal v) {
        BigDecimal abs = v.abs();
        if (abs.compareTo(MILLION) >= 0) return num(v.divide(MILLION, 1, RoundingMode.HALF_UP)) + " млн ₸";
        if (abs.compareTo(THOUSAND) >= 0) return num(v.divide(THOUSAND, 0, RoundingMode.HALF_UP)) + " тыс. ₸";
        return num(v.setScale(0, RoundingMode.HALF_UP)) + " ₸";
    }

    private static String num(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String join(String sep, String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        return b == null || b.isEmpty() ? a : a + sep + b;
    }

    private static String esc(String s) { return TelegramText.html(s); }

    static int visible(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").length();
    }
}
