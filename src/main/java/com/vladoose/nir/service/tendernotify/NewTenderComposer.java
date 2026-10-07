package com.vladoose.nir.service.tendernotify;

import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.util.DocFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Текст уведомления о новых тендерах (чистая функция): ОДНО сообщение на прогон — бот и группа общие с сайтом,
 * а сайт при отказе Telegram своё уведомление о заявке теряет, поэтому лишних отправок не делаем. Тендеры, которые
 * не помещаются в предел, — строкой «…и ещё N» со ссылкой на раздел. Все поля — одной строкой
 * ({@link TelegramText#oneLine}): их пишет площадка.
 */
final class NewTenderComposer {

    /** С запасом до предела Telegram 4096. */
    static final int MAX_TEXT = 3500;
    private static final int SHOWN_LOTS = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    record LotLine(String name, Integer qty) {}

    record Card(long tenderId, String platformLabel, String number, String customer, String region, String subject,
                BigDecimal total, LocalDate deadline, List<LotLine> lots) {}

    private NewTenderComposer() {
    }

    /** null — показывать нечего. publicUrl пустой — без ссылок. */
    static String compose(List<Card> cards, String publicUrl) {
        if (cards.isEmpty()) return null;
        String base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
        StringBuilder out = new StringBuilder(cards.size() == 1 ? "🏥 Новый тендер" : "🏥 Новые тендеры: " + cards.size());
        int reserve = tail(cards.size(), base).length();
        int shown = 0;
        for (Card c : cards) {
            String block = (shown == 0 ? "\n" : "\n\n") + block(c, base);
            boolean last = shown == cards.size() - 1;
            if (out.length() + block.length() + (last ? 0 : reserve) > MAX_TEXT) break;
            out.append(block);
            shown++;
        }
        if (shown < cards.size()) out.append(tail(cards.size() - shown, base));
        return out.toString();
    }

    private static String tail(int rest, String base) {
        return "\n\n…и ещё " + rest + " — раздел «Тендеры» в АИС" + (base == null ? "" : ": " + base + "/tenders?market=KZ");
    }

    private static String block(Card c, String base) {
        StringBuilder b = new StringBuilder("▸ ").append(blankTo(TelegramText.oneLine(c.subject(), 200), "Без названия"));
        String who = join(TelegramText.oneLine(c.customer(), 150), TelegramText.oneLine(c.region(), 60));
        if (!who.isEmpty()) b.append('\n').append(who);
        if (!c.lots().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (LotLine l : c.lots().subList(0, Math.min(SHOWN_LOTS, c.lots().size()))) {
                parts.add(blankTo(TelegramText.oneLine(l.name(), 80), "без наименования")
                        + (l.qty() == null ? "" : " — " + l.qty() + " шт."));
            }
            b.append("\nЛоты (").append(c.lots().size()).append("): ").append(String.join("; ", parts));
            if (c.lots().size() > SHOWN_LOTS) b.append("; и ещё ").append(c.lots().size() - SHOWN_LOTS);
        }
        String money = c.total() == null ? "" : "Сумма: " + DocFormat.money(c.total()) + " ₸";
        String until = c.deadline() == null ? "" : (money.isEmpty() ? "Приём до " : "приём до ") + c.deadline().format(DATE);
        String sum = join(money, until);
        if (!sum.isEmpty()) b.append('\n').append(sum);
        b.append('\n').append(TelegramText.oneLine(c.platformLabel(), 40)).append(" № ")
                .append(blankTo(TelegramText.oneLine(c.number(), 60), "—"));
        if (base != null) {
            b.append("\nОткрыть в АИС: ").append(base).append("/tenders?openId=").append(c.tenderId()).append("&market=KZ");
        }
        return b.toString();
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        return b.isEmpty() ? a : a + " · " + b;
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
