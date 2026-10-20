package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Что из «нашей продукции» уходит в Telegram — решение оператора 2026-10-20 после первого прогона на проде («сыпет всё
 * подряд»): режим (все отметки / только «★» / ничего) и порог суммы НАШИХ лотов тендера или пункта плана — «чтоб на всякий
 * шлак время не тратить». Отметки в АИС и фильтр «Наш товар» режим не трогает — только сообщения.
 *
 * <p>Настройки: {@code PRODUCT_WATCH_NOTIFY} — ALL / STAR / OFF, пусто — STAR; {@code PRODUCT_WATCH_NOTIFY_MIN_SUM} — ₸,
 * пусто — 1 000 000, 0 — без порога. Опечатка не роняет старт и не включает поток сообщений: остаётся тихое умолчание,
 * в лог — WARN.
 */
public record ProductNotifyPolicy(Mode mode, BigDecimal minSum) {

    private static final Logger log = LoggerFactory.getLogger(ProductNotifyPolicy.class);

    public enum Mode {
        /** Все отметки, в том числе «🏷 наш вид». */
        ALL,
        /** Только «★»: назван наш товар или ТЗ списано с него. */
        STAR,
        /** Ничего. */
        OFF
    }

    public static final BigDecimal DEFAULT_MIN_SUM = new BigDecimal("1000000");

    public static ProductNotifyPolicy of(String mode, String minSum) {
        return new ProductNotifyPolicy(mode(mode), minSum(minSum));
    }

    private static Mode mode(String s) {
        if (s == null || s.isBlank()) return Mode.STAR;
        try {
            return Mode.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("PRODUCT_WATCH_NOTIFY: неизвестный режим «{}» (ALL / STAR / OFF) — шлю только «★»", s.strip());
            return Mode.STAR;
        }
    }

    private static BigDecimal minSum(String s) {
        if (s == null || s.isBlank()) return DEFAULT_MIN_SUM;
        try {
            BigDecimal v = new BigDecimal(s.replaceAll("[\\s\\u00a0]", ""));
            if (v.signum() >= 0) return v;
        } catch (NumberFormatException ignored) {
            // ниже — WARN и умолчание
        }
        log.warn("PRODUCT_WATCH_NOTIFY_MIN_SUM: «{}» — не сумма в тенге; порог — {} ₸", s.strip(), DEFAULT_MIN_SUM.toPlainString());
        return DEFAULT_MIN_SUM;
    }

    /** Уровень отметки (наибольший у тендера или пункта плана) идёт в сообщение. */
    public boolean levelPasses(ProductMatchLevel level) {
        if (level == null) return false;
        return switch (mode) {
            case ALL -> true;
            case STAR -> level.exact();
            case OFF -> false;
        };
    }

    /** Сумма наших лотов не меньше порога; неизвестна — не доказано, не проходит (порог 0 — проходит всё). */
    public boolean sumPasses(BigDecimal sum) {
        if (minSum.signum() == 0) return true;
        return sum != null && sum.compareTo(minSum) >= 0;
    }
}
