package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.util.DocFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Текст «Наш товар в тендерах» (спека 2026-10-19-product-watch §9, чистая функция): одно сообщение на прогон — сверху
 * «★ Точно наше», ниже «✅ Подходит наш товар» (сверка ТЗ, спека 2026-10-20-product-spec-fit §7) и «🏷 Наш вид изделия»;
 * повышение у уже сообщённого тендера — короткое «★ Уточнение» («✅ Уточнение», если повышение только до «подходит»). Со звуком, если есть ★ или «подходит». Лоты — только
 * наши. Поля — одной строкой ({@link TelegramText#oneLine}): их пишет площадка.
 */
public final class ProductWatchComposer {

    /** С запасом до предела Telegram 4096. */
    public static final int MAX_TEXT = 3500;
    private static final int SHOWN_LOTS = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** upgrade — тендер уже сообщён уровнем ниже; lots — наши лоты (у уточнения — только точные). */
    public record Card(long tenderId, String number, String platformLabel, String tradeMethod, String subject, String customer,
                       String region, BigDecimal total, LocalDate deadline, LocalDateTime deadlineAt, ProductMatchLevel level,
                       boolean upgrade, List<ProductMatchQuery.LotLine> lots, BigDecimal ourSum) {
        /** Без суммы наших лотов — прежние вызовы (тесты сообщения «Наш товар»). */
        public Card(long tenderId, String number, String platformLabel, String tradeMethod, String subject, String customer,
                    String region, BigDecimal total, LocalDate deadline, LocalDateTime deadlineAt, ProductMatchLevel level,
                    boolean upgrade, List<ProductMatchQuery.LotLine> lots) {
            this(tenderId, number, platformLabel, tradeMethod, subject, customer, region, total, deadline, deadlineAt, level,
                    upgrade, lots, null);
        }
    }

    /**
     * shown — тендеры, вошедшие в текст (остальные — строкой «…и ещё N»): сообщёнными отмечаются только они.
     * Звук — если среди показанных есть ★.
     */
    public record Message(String text, boolean sound, List<Long> shown) {}

    private ProductWatchComposer() {}

    /** null — показывать нечего. publicUrl пустой — без ссылок. Порядок карточек внутри разделов — как пришли. */
    public static Message compose(List<Card> cards, String publicUrl) {
        if (cards.isEmpty()) return null;
        String base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
        List<Card> exact = cards.stream().filter(c -> !c.upgrade() && c.level().exact()).toList();
        List<Card> fits = cards.stream().filter(c -> !c.upgrade() && c.level() == ProductMatchLevel.FITS).toList();
        List<Card> upgrades = cards.stream().filter(Card::upgrade).toList();
        List<Card> type = cards.stream().filter(c -> !c.upgrade() && c.level() == ProductMatchLevel.TYPE).toList();
        int fresh = exact.size() + fits.size() + type.size();
        // «наш вид» → «подходит» — не «ТЗ под наш товар»: ТЗ не списано, наш товар проходит по требованиям
        boolean exactUp = upgrades.stream().anyMatch(c -> c.level().exact());
        StringBuilder out = new StringBuilder(fresh == 0
                ? (exactUp ? "★ Уточнение: ТЗ под наш товар" : "✅ Уточнение: подходит наш товар")
                        + (upgrades.size() > 1 ? " — " + upgrades.size() : "")
                : "🏷 Наш товар в тендерах: " + fresh);
        String tail = tail(base);
        List<Card> shown = new ArrayList<>();
        section(out, fresh == 0 ? null : "★ Точно наше", exact, base, tail, shown);
        section(out, fresh == 0 ? null : "✅ Подходит наш товар", fits, base, tail, shown);
        section(out, fresh == 0 ? null : exactUp ? "★ Уточнение" : "✅ Уточнение", upgrades, base, tail, shown);
        section(out, "🏷 Наш вид изделия", type, base, tail, shown);
        if (shown.size() < cards.size()) out.append("\n\n…и ещё ").append(cards.size() - shown.size()).append(tail);
        boolean sound = shown.stream().anyMatch(c -> c.level().alert());
        return new Message(out.toString(), sound, shown.stream().map(Card::tenderId).toList());
    }

    /** Раздел: заголовок (null — без заголовка: сообщение из одних уточнений) и карточки, пока помещаются с хвостом. */
    private static void section(StringBuilder out, String head, List<Card> cards, String base, String tail, List<Card> shown) {
        if (cards.isEmpty()) return;
        String h = head == null ? "" : "\n\n" + head;
        String first = "\n" + block(cards.get(0), base);
        // заголовок — только вместе с первой карточкой: пустой раздел в конце сообщения не нужен
        if (out.length() + h.length() + first.length() + tail.length() + 20 > MAX_TEXT) return;
        out.append(h);
        for (Card c : cards) {
            String b = "\n" + block(c, base);
            if (out.length() + b.length() + tail.length() + 20 > MAX_TEXT) break;
            out.append(b);
            shown.add(c);
        }
    }

    private static String tail(String base) {
        return " — фильтр «Наш товар» в АИС" + (base == null ? "" : ": " + base + "/tenders?our=ANY&market=KZ");
    }

    private static String block(Card c, String base) {
        StringBuilder b = new StringBuilder("▸ ").append(blank(TelegramText.oneLine(c.subject(), 200), "Без названия"));
        String who = join(TelegramText.oneLine(c.customer(), 150), TelegramText.oneLine(c.region(), 60));
        if (!who.isEmpty()) b.append('\n').append(who);
        List<String> parts = new ArrayList<>();
        for (ProductMatchQuery.LotLine l : c.lots().subList(0, Math.min(SHOWN_LOTS, c.lots().size()))) {
            String lot = blank(TelegramText.oneLine(l.lotName(), 80), "лот") + (l.qty() == null ? "" : " — " + l.qty() + " шт.");
            String what = switch (l.level()) {
                case COPIED -> "ТЗ списано с нашего товара: "
                        + TelegramText.oneLine(ProductMatchQuery.shortName(l.productName() == null ? l.word() : l.productName()), 100)
                        + countTail(l.evidence());
                case NAMED -> "назван наш товар: " + TelegramText.oneLine(ProductMatchQuery.what(l), 100);
                case FITS -> "подходит наш товар: " + TelegramText.oneLine(ProductMatchQuery.shortName(
                        l.fit() == null ? l.word() : l.fit().productName()), 100) + ProductMatchQuery.metTail(l.fit());
                case TYPE -> l.similar() == null || l.similar().isEmpty() ? "" : "похоже: " + l.similar().stream().limit(2)
                        .map(x -> TelegramText.oneLine(ProductMatchQuery.shortName(x.name()), 60)
                                + (x.brand() == null || x.brand().isBlank() ? "" : " (" + TelegramText.oneLine(x.brand(), 30) + ")"))
                        .collect(Collectors.joining(", "));
            };
            String line = what.isEmpty() ? lot : lot + " · " + what;
            if (l.level() == ProductMatchLevel.FITS && l.fit() != null && l.fit().foreignBrand() != null) {
                line += "\nТЗ под " + TelegramText.oneLine(l.fit().foreignBrand(), 40) + " — наш аналог по ТЗ проходит";
            }
            parts.add(line);
        }
        if (!parts.isEmpty()) b.append('\n').append(String.join("\n", parts));
        if (c.lots().size() > SHOWN_LOTS) b.append("\nи ещё ").append(c.lots().size() - SHOWN_LOTS).append(" наших лотов");
        String money = c.total() == null ? "" : "Сумма: " + DocFormat.money(c.total()) + " ₸";
        String deadline = c.deadlineAt() != null ? c.deadlineAt().format(DATE_TIME) : c.deadline() != null ? c.deadline().format(DATE) : null;
        String until = deadline == null ? "" : (money.isEmpty() ? "Приём до " : "приём до ") + deadline;
        String sum = join(money, until);
        if (!sum.isEmpty()) b.append('\n').append(sum);
        b.append('\n').append(TelegramText.oneLine(c.platformLabel(), 40)).append(" № ").append(blank(TelegramText.oneLine(c.number(), 60), "—"));
        String method = method(c.tradeMethod());
        if (method != null) b.append(" · ").append(method);
        if (base != null) b.append("\nОткрыть в АИС: ").append(base).append("/tenders?openId=").append(c.tenderId()).append("&market=KZ");
        return b.toString();
    }

    /** Способ — если не обычный ЗЦП: «из одного источника», «конкурс», «аукцион», «электронный магазин». */
    static String method(String m) {
        if (m == null || m.isBlank()) return null;
        String low = m.toLowerCase(Locale.ROOT);
        if (low.contains("одного источника")) return "из одного источника";
        if (low.contains("конкурс")) return "конкурс";
        if (low.contains("аукцион")) return "аукцион";
        if (low.contains("магазин")) return "электронный магазин";
        return null;
    }

    /** «совпало 5 фраз: …» → « (совпало 5 фраз)». */
    private static String countTail(String evidence) {
        if (evidence == null) return "";
        int i = evidence.indexOf("совпало ");
        if (i < 0) return "";
        int colon = evidence.indexOf(':', i);
        return " (" + TelegramText.oneLine(evidence.substring(i, colon < 0 ? evidence.length() : colon), 40) + ")";
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        return b == null || b.isEmpty() ? a : a + " · " + b;
    }

    private static String blank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
