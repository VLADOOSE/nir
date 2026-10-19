package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.offer.OfferSettingsValidator;
import com.vladoose.nir.service.supplierreply.MoneyText;
import com.vladoose.nir.service.supplierreply.ReplyRowValues;
import com.vladoose.nir.service.supplierreply.ReplyTableReader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Строки готового КП из таблиц файла (спека 2026-10-19 §4.2, решение при планировании 5), по порядку правил:
 * одна надпись во всю строку — раздел (остаётся, только если под ним есть позиция или «включено»); цена есть —
 * позиция; цены нет, а в строке «включено» — «включено»; остальное — пропуск. Валюта таблицы не рынка — позиции без цены.
 */
public final class OfferFileLines {

    /** Наименование строки КП — TEXT; длиннее не бывает у настоящих КП, а описание на страницу ломает таблицу. */
    static final int NAME_MAX = 2000;
    static final int UNIT_MAX = 30;
    /** Предел колонки количества NUMERIC(12,3). */
    static final BigDecimal QTY_MAX = new BigDecimal("999999999.999");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*%");
    private static final Pattern BARE = Pattern.compile("^\\s*(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*$");
    /** «Итого: 9 902 248,23» абзацем под таблицей — только итог целиком («Итого без НДС» — нет). */
    private static final Pattern TOTAL_LINE = Pattern.compile(
            "(?iu)^\\s*(?:итого|всего)(?:\\s+(?:к\\s+оплате|с\\s+ндс|с\\s+уч[её]том\\s+ндс))?\\s*:?\\s*(?<sum>\\d.*)$");

    public record Lines(List<OfferFileLine> lines, BigDecimal fileTotal, String foreignCurrency) {}

    /** Ставка из колонки НДС: found = false — колонки нет, ставка не распознана или её нет в списке рынка. */
    public record VatPick(boolean found, BigDecimal rate) {
        static final VatPick NONE = new VatPick(false, null);
    }

    private OfferFileLines() {}

    public static Lines of(List<ReplyTableReader.ReadTable> tables, String marketCurrency) {
        List<OfferFileLine> out = new ArrayList<>();
        BigDecimal total = null;
        String foreign = null;
        for (ReplyTableReader.ReadTable rt : tables) {
            ReplyTable t = rt.table();
            boolean ours = t.currency() == null || t.currency().equals(marketCurrency);
            if (!ours && foreign == null) foreign = t.currency();
            if (rt.total() != null) total = total == null ? rt.total() : total.add(rt.total());
            out.addAll(table(t, ours));
        }
        return new Lines(out, total, foreign);
    }

    static List<OfferFileLine> table(ReplyTable t, boolean pricesOk) {
        List<ReplyRowValues> values = ReplyRowValues.of(t);
        List<OfferFileLine> out = new ArrayList<>();
        int pending = -1;   // раздел, под которым ещё нет ни позиции, ни «включено»
        for (int i = 0; i < t.rows().size(); i++) {
            List<String> cells = t.rows().get(i).cells();
            Set<String> texts = new LinkedHashSet<>();
            for (String c : cells) if (c != null && !c.isBlank()) texts.add(c.strip());
            if (texts.isEmpty()) continue;
            if (texts.size() == 1 && hasLetter(texts.iterator().next())) {
                if (pending >= 0) out.remove(pending);
                out.add(new OfferFileLine(ClientOfferItemKind.SECTION, cut(texts.iterator().next(), NAME_MAX),
                        null, null, null, null, null, null));
                pending = out.size() - 1;
                continue;
            }
            ReplyRowValues v = values.get(i);
            if (v.name() == null) continue;
            if (v.price() != null) {
                out.add(item(v, pricesOk));
                pending = -1;
            } else if (included(cells)) {
                out.add(new OfferFileLine(ClientOfferItemKind.INCLUDED, cut(v.name(), NAME_MAX), null, null, null, null, null, null));
                pending = -1;
            }
        }
        if (pending >= 0) out.remove(pending);
        return out;
    }

    private static OfferFileLine item(ReplyRowValues v, boolean pricesOk) {
        BigDecimal q = v.quantity() == null || v.quantity().value().signum() <= 0
                || v.quantity().value().compareTo(QTY_MAX) > 0 ? BigDecimal.ONE : v.quantity().value();
        String unit = v.unit() == null || v.unit().isBlank() ? "шт" : cut(v.unit().strip(), UNIT_MAX);
        return new OfferFileLine(ClientOfferItemKind.ITEM, cut(v.name(), NAME_MAX), cut(v.article(), 255), cut(v.brand(), 500),
                q.setScale(3, RoundingMode.HALF_UP), unit, pricesOk ? v.price() : null, v.vat());
    }

    /**
     * Итог КП из текста под таблицей, когда в таблице строки «ИТОГО» нет (бланк отца в КП на ВитаЛайн 200): строка
     * «Итого: …» / «Всего к оплате: …». Разные суммы в таких строках — не угадываем (null).
     */
    public static BigDecimal textTotal(List<String> paragraphs) {
        BigDecimal found = null;
        for (String p : paragraphs) {
            if (p == null) continue;
            Matcher m = TOTAL_LINE.matcher(p.replace('\u00a0', ' ').strip());
            if (!m.find()) continue;
            BigDecimal v = MoneyText.amount(m.group("sum"));
            if (v == null) continue;
            if (found != null && found.compareTo(v) != 0) return null;
            found = v;
        }
        return found;
    }

    public static VatPick vat(String text, List<BigDecimal> rates) {
        if (text == null || text.isBlank()) return VatPick.NONE;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("без ндс") || t.contains("не облага") || t.strip().equals("без")) {
            return OfferSettingsValidator.containsRate(rates, null) ? new VatPick(true, null) : VatPick.NONE;
        }
        Matcher m = PERCENT.matcher(t);
        String num = m.find() ? m.group(1) : null;
        if (num == null) {
            Matcher b = BARE.matcher(t);
            if (b.find()) num = b.group(1);
        }
        if (num == null || rates == null) return VatPick.NONE;
        BigDecimal r = new BigDecimal(num.replace(',', '.'));
        for (BigDecimal x : rates) if (x != null && x.compareTo(r) == 0) return new VatPick(true, x);
        return VatPick.NONE;
    }

    private static boolean included(List<String> cells) {
        for (String c : cells) if (c != null && c.toLowerCase(Locale.ROOT).contains("включен")) return true;
        return false;
    }

    private static boolean hasLetter(String s) {
        return s.chars().anyMatch(Character::isLetter);
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
