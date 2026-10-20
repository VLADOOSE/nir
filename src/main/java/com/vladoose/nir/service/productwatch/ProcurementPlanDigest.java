package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProcurementPlanPoint;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Сводка «🗓 Новое в планах закупок» раз в день (спека 2026-10-19-product-watch §10.3): тихо, в тему «Тендеры»;
 * первый сбор — молча (иначе в тему высыпался бы весь план страны). Не ушло — пункты не помечаются, следующий сбор повторит.
 */
@Component
public class ProcurementPlanDigest {

    private static final Logger log = LoggerFactory.getLogger(ProcurementPlanDigest.class);

    static final int MAX_LINES = 10;
    static final int LIMIT = 3500;
    static final String[] MONTHS = {"январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь",
            "октябрь", "ноябрь", "декабрь"};

    public interface Sender {
        void send(String text, boolean silent);
    }

    private final ProcurementPlanPointRepository points;
    private final TransactionTemplate tx;
    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    private final String publicUrl;
    private final Clock clock;

    @Autowired
    public ProcurementPlanDigest(ProcurementPlanPointRepository points, PlatformTransactionManager tm, TelegramSettings telegram,
                                 TelegramClient client, @Value("${product-watch.enabled:false}") boolean enabled,
                                 @Value("${ais.public-url:}") String publicUrl) {
        this(points, tm, telegram, client::sendTenders, enabled, publicUrl, Clock.systemUTC());
    }

    public ProcurementPlanDigest(ProcurementPlanPointRepository points, PlatformTransactionManager tm, TelegramSettings telegram,
                                 Sender sender, boolean enabled, String publicUrl, Clock clock) {
        this.points = points; this.tx = new TransactionTemplate(tm); this.telegram = telegram; this.sender = sender;
        this.enabled = enabled; this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", ""); this.clock = clock;
    }

    public synchronized void flush(boolean firstRun, ImportSummary sum) {
        if (!enabled) return;
        List<ProcurementPlanPoint> fresh;
        try {
            fresh = tx.execute(st -> points.findByDigestedAtIsNullAndGoneAtIsNull());
        } catch (RuntimeException e) {
            sum.addSideError("сводка планов не собрана: " + ErrorText.of(e));
            return;
        }
        if (fresh == null || fresh.isEmpty()) return;
        List<Long> ids = fresh.stream().map(ProcurementPlanPoint::getId).toList();
        if (firstRun || !telegram.isConfigured()) {   // первый сбор и выключенный Telegram — без сообщения, без накопления
            markDigested(ids);
            return;
        }
        List<ProcurementPlanPoint> sorted = fresh.stream()
                .sorted(Comparator.comparing((ProcurementPlanPoint p) -> p.getLevel() == ProductMatchLevel.TYPE ? 1 : 0)
                        .thenComparing(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount(), Comparator.reverseOrder()))
                .toList();
        try {
            sender.send(compose(sorted, publicUrl), true);
        } catch (RuntimeException e) {
            log.warn("Telegram: сводка планов ({}) не ушла, повтор — со следующим сбором: {}", ids.size(), ErrorText.of(e));
            sum.addSideError("сводка планов не ушла (повтор — со следующим сбором): " + ErrorText.of(e));
            return;
        }
        markDigested(ids);
        log.info("Telegram: сводка планов отправлена ({})", ids.size());
    }

    private void markDigested(List<Long> ids) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        tx.executeWithoutResult(st -> points.findAllById(ids).forEach(p -> p.setDigestedAt(now)));
    }

    static String compose(List<ProcurementPlanPoint> ps, String base) {
        StringBuilder b = new StringBuilder("🗓 Новое в планах закупок: ").append(ps.size());
        int shown = 0;
        for (ProcurementPlanPoint p : ps) {
            if (shown >= MAX_LINES) break;
            String block = block(p);
            if (b.length() + block.length() + 200 > LIMIT) break;
            b.append("\n\n").append(block);
            shown++;
        }
        int rest = ps.size() - shown;
        String link = base.isEmpty() ? "раздел «Планы закупок» в АИС" : "«Планы закупок» в АИС: " + base + "/procurement-plans?fresh=1&market=KZ";
        b.append("\n\n").append(rest > 0 ? "…и ещё " + rest + " — " + link : link);
        return b.toString();
    }

    /** Единица плана коротко: «Штука» → «шт.», «Комплект» → «компл.»; прочие — как есть, строчными. */
    static String unit(String u) {
        if (u == null || u.isBlank()) return "";
        String s = u.strip().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "штука" -> "шт.";
            case "штука условная" -> "усл. шт.";
            case "комплект" -> "компл.";
            case "упаковка" -> "уп.";
            case "рулон" -> "рул.";
            default -> TelegramText.oneLine(s, 30);
        };
    }

    static String block(ProcurementPlanPoint p) {
        List<String> parts = new ArrayList<>();
        if (p.getQuantity() != null) {
            String unit = unit(p.getUnit());
            parts.add(p.getQuantity().stripTrailingZeros().toPlainString() + (unit.isEmpty() ? "" : " " + unit));
        }
        if (p.getAmount() != null) parts.add(DocFormat.money(p.getAmount()) + " ₸");
        String when = p.getMonth() != null && p.getMonth() >= 1 && p.getMonth() <= 12 ? MONTHS[p.getMonth() - 1] + " " + p.getYear() : String.valueOf(p.getYear());
        String who = String.join(" · ", Arrays.asList(p.getCustomerName(), p.getRegion()).stream().filter(Objects::nonNull).toList());
        return "▸ " + (p.getLevel() == ProductMatchLevel.TYPE ? "" : "★ ") + TelegramText.oneLine(p.getName(), 200)
                + (parts.isEmpty() ? "" : " — " + String.join(", ", parts)) + " · " + when
                + (who.isEmpty() ? "" : "\n  " + TelegramText.oneLine(who, 300));
    }
}
