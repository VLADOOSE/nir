package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ProtocolStatus;
import com.vladoose.nir.entity.TenderLotResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Тексты итогов (спека 2026-10-22-tender-results-market-prices §7, §11): событие («История», Telegram, сводка) — без названия
 * лота и заказчика, их добавляет место показа. Числа — с неразрывными пробелами. Чистые функции.
 */
public final class ResultTexts {

    static final char NBSP = '\u00A0';
    private static final BigDecimal THOUSAND = new BigDecimal(1000);
    private static final BigDecimal MILLION = new BigDecimal(1_000_000);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private ResultTexts() {}

    /** «251 000 ₸», «4 500,50 ₸», «3,7 млн ₸»; пусто — «—». */
    public static String money(BigDecimal v) {
        if (v == null) return "—";
        if (v.abs().compareTo(MILLION) >= 0) return num(v.divide(MILLION, 2, RoundingMode.HALF_UP)) + NBSP + "млн" + NBSP + "₸";
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        BigDecimal abs = r.abs();
        String whole = group(abs.setScale(0, RoundingMode.DOWN).toBigInteger().toString());
        int cents = abs.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        return (r.signum() < 0 ? "−" : "") + whole + (cents == 0 ? "" : "," + (cents < 10 ? "0" : "") + cents) + NBSP + "₸";
    }

    /** «251–613 тыс.», «1,2–3,4 млн», «900–950 ₸» (вниз до целого: «от … до …» не завышаем); равные — одно число. */
    public static String range(BigDecimal min, BigDecimal max) {
        if (min == null || max == null) return money(min == null ? max : min);
        if (min.compareTo(max) == 0) return money(min);
        if (max.compareTo(MILLION) >= 0) {
            return num(min.divide(MILLION, 2, RoundingMode.DOWN)) + "–" + num(max.divide(MILLION, 2, RoundingMode.DOWN)) + NBSP + "млн";
        }
        if (min.compareTo(THOUSAND) >= 0) {
            return min.divide(THOUSAND, 0, RoundingMode.DOWN).toPlainString() + "–"
                    + max.divide(THOUSAND, 0, RoundingMode.DOWN).toPlainString() + NBSP + "тыс.";
        }
        return num(min.setScale(2, RoundingMode.HALF_UP)) + "–" + num(max.setScale(2, RoundingMode.HALF_UP)) + NBSP + "₸";
    }

    /** Единица: своя у лота (частная заявка), иначе пункта плана; «Штука» → «шт». Пусто — «шт». */
    public static String unit(String planUnit, String lotUnit) {
        String u = lotUnit != null && !lotUnit.isBlank() ? lotUnit : planUnit;
        if (u == null || u.isBlank()) return "шт";
        String t = u.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "штука", "шт", "шт." -> "шт";
            case "упаковка", "уп", "уп.", "упак", "упак." -> "уп";
            case "комплект", "компл", "компл." -> "компл";
            case "килограмм", "кг" -> "кг";
            case "литр", "л" -> "л";
            case "метр", "м" -> "м";
            case "флакон", "фл", "фл." -> "фл";
            default -> t.length() <= 12 ? t : t.substring(0, 12);
        };
    }

    /** Способ по номеру лота: «88208881-ЗЦП2» → «ЗЦП», «…-ОИ3» → «ОИ», «…-ОК1» → «ОК». */
    public static String method(String lotNumber) {
        if (lotNumber == null || !lotNumber.contains("-")) return null;
        String tail = lotNumber.substring(lotNumber.lastIndexOf('-') + 1).replaceAll("\\d", "").trim();
        return tail.isEmpty() ? null : tail;
    }

    public static String methodName(String method) {
        if (method == null) return "закупка";
        return switch (method) {
            case "ЗЦП" -> "запрос цен";
            case "ОИ" -> "из одного источника";
            case "ОК" -> "конкурс";
            case "ЭА", "А" -> "аукцион";
            default -> method;
        };
    }

    /** «1 заявка», «3 заявки», «10 заявок». */
    public static String bids(int n) {
        int d = n % 10, h = n % 100;
        String w = d == 1 && h != 11 ? "заявка" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "заявки" : "заявок";
        return n + " " + w;
    }

    /** «победил ИП X — 251 000 ₸ за шт · 10 заявок (251–613 тыс.) · бюджет 613 900 ₸»; протокол не разобран — «состоялся». */
    public static String done(TenderLotResult r, String unit, BigDecimal budget) {
        StringBuilder b = new StringBuilder();
        if (r.getWinnerName() != null || r.getWinnerBin() != null) {
            b.append("победил ").append(who(r.getWinnerName(), r.getWinnerBin()));
            if (r.getWinnerPrice() != null) b.append(" — ").append(money(r.getWinnerPrice())).append(" за ").append(unit);
        } else {
            b.append("состоялся");
        }
        if (r.getBidsCount() != null && r.getBidsCount() > 0) {
            b.append(" · ").append(bids(r.getBidsCount()));
            if (r.getMinPrice() != null && r.getMaxPrice() != null && r.getMinPrice().compareTo(r.getMaxPrice()) != 0) {
                b.append(" (").append(range(r.getMinPrice(), r.getMaxPrice())).append(')');
            }
        }
        if (budget != null) b.append(" · бюджет ").append(money(budget));
        return b.toString();
    }

    public static String noBids() {
        return "никто не подал — объявят заново";
    }

    public static String oneBid(String name, BigDecimal price, String unit) {
        return "1 заявка → из одного источника у " + (name == null ? "единственного участника" : name)
                + (price == null ? "" : ", " + money(price) + " за " + unit);
    }

    /** «не состоялся (<причина из вывода протокола>) — объявят заново». */
    public static String failedOther(String failText, ProtocolStatus status) {
        String why = null;
        if (failText != null) {
            int i = failText.indexOf("в связи с");
            why = i < 0 ? null : failText.substring(i + "в связи с".length()).trim().replaceAll("[.;]+$", "");
            if (why != null && why.length() > 150) why = why.substring(0, 150).trim() + "…";
        }
        if (why == null || why.isEmpty()) why = status == ProtocolStatus.PARSED ? "причина — в протоколе" : "протокол не разобран";
        return "не состоялся (" + why + ") — объявят заново";
    }

    public static String cancelled() {
        return "отменён";
    }

    public static String contract(String supplier, BigDecimal unitPrice, String unit, String number, LocalDate signedAt) {
        StringBuilder b = new StringBuilder("договор с ").append(supplier == null ? "поставщиком" : supplier);
        if (unitPrice != null) b.append(" — ").append(money(unitPrice)).append(" за ").append(unit);
        if (number != null && !number.isBlank()) b.append(", № ").append(number.trim());
        if (signedAt != null) b.append(" от ").append(signedAt.format(DATE));
        return b.toString();
    }

    public static String repeat(String anno, String method, LocalDateTime endAt) {
        return "объявлена повторная попытка № " + anno + " (" + method + ")" + (endAt == null ? "" : ", приём до " + endAt.format(DAY_TIME));
    }

    public static String oiDone(String supplier) {
        return "из одного источника" + (supplier == null ? "" : " у " + supplier) + " — состоялся";
    }

    public static String oiFailed(String supplier) {
        return "из одного источника сорвалось (" + (supplier == null ? "поставщик" : supplier) + " не подписал) — объявят заново";
    }

    /** Наша заявка в протоколе: выиграли / место N из M с разницей к победителю / отклонена. */
    public static String ourBid(boolean won, Integer place, Integer total, BigDecimal ourPrice, String winnerName, BigDecimal winnerPrice,
                                boolean rejected, String rejectReason, String unit) {
        if (rejected) return "⛔ Наша заявка отклонена" + (rejectReason == null || rejectReason.isBlank() ? "" : ": " + rejectReason.trim());
        String price = ourPrice == null ? "" : "наша цена " + money(ourPrice) + " за " + unit;
        if (won) return "🏆 Выиграли" + (price.isEmpty() ? "" : ": " + price);
        String where = "место " + (place == null ? "?" : place) + " из " + (total == null ? "?" : total);
        if (winnerName == null && winnerPrice == null) return "📋 Наша заявка: " + where + (price.isEmpty() ? "" : ", " + price);
        StringBuilder b = new StringBuilder("📉 Наше ").append(where).append(':');
        if (!price.isEmpty()) b.append(' ').append(price).append(',');
        b.append(" победил ").append(winnerName == null ? "другой участник" : winnerName);
        if (winnerPrice != null) {
            b.append(" — ").append(money(winnerPrice));
            if (ourPrice != null && ourPrice.signum() > 0) {
                long pct = winnerPrice.subtract(ourPrice).multiply(BigDecimal.valueOf(100)).divide(ourPrice, 0, RoundingMode.HALF_UP).longValue();
                b.append(" (").append(pct < 0 ? "−" + (-pct) : "+" + pct).append(NBSP).append("%)");
            }
        }
        return b.toString();
    }

    /** Коротко для чипа: «251 тыс. ₸», «1,3 млн ₸», «950 ₸». */
    public static String compact(BigDecimal v) {
        if (v == null) return "";
        BigDecimal abs = v.abs();
        if (abs.compareTo(MILLION) >= 0) return num(v.divide(MILLION, 1, RoundingMode.HALF_UP)) + " млн ₸";
        if (abs.compareTo(THOUSAND) >= 0) return v.divide(THOUSAND, 0, RoundingMode.HALF_UP).toPlainString() + " тыс. ₸";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₸";
    }

    /** Название, иначе «БИН …». */
    public static String who(String name, String bin) {
        if (name != null && !name.isBlank()) return name.trim();
        return bin == null ? "участник" : "БИН " + bin;
    }

    private static String num(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    static String group(String digits) {
        StringBuilder b = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) b.append(NBSP);
            b.append(digits.charAt(i));
        }
        return b.toString();
    }
}
