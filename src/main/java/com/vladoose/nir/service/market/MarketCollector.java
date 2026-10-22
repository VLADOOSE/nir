package com.vladoose.nir.service.market;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.integration.goszakup.dto.SearchPage;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Набор цен рынка (спека 2026-10-22-tender-results-market-prices §9): раз в сутки — по словам групп «нашей продукции»; живой
 * запрос — слово, не обновлявшееся сутки (до budget); история заказчика — его договоры за 2 года. Сеть — здесь, вне транзакций;
 * запись — {@link MarketContractWriter}. Пауза 300 мс; 401 / 403 / 429 — стоп (токен общий с продом).
 */
@Service
public class MarketCollector {

    private static final Logger log = LoggerFactory.getLogger(MarketCollector.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String DAILY = "daily";
    static final int WORD_PAGES = 5;
    static final int CONTRACT_BATCH = 100;
    static final int LOTS_BATCH = 200;
    static final long PAUSE_MS = 300;
    static final Duration FRESH = Duration.ofHours(24);
    static final Duration ERROR_BACKOFF = Duration.ofMinutes(10);
    static final int YEARS = 2;
    static final int DOWN_STOP = 3;

    /** Состояние запроса: ok — данные свежие; fetchedAt — когда обновлялись; error — что не вышло. */
    public record Lookup(boolean ok, OffsetDateTime fetchedAt, String error) {}

    public record Collected(int rows, boolean complete) {}

    private final GoszakupResultsClient client;
    private final MarketContractWriter writer;
    private final GoszakupSubjects subjects;
    private final Supplier<List<String>> words;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public MarketCollector(GoszakupResultsClient client, MarketContractWriter writer, GoszakupSubjects subjects, CatalogSource catalog) {
        this(client, writer, subjects, () -> catalog.get().groups().stream().flatMap(g -> g.searchWords().stream())
                .map(w -> w.strip().toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).distinct().toList(), Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public MarketCollector(GoszakupResultsClient client, MarketContractWriter writer, GoszakupSubjects subjects, Supplier<List<String>> words,
                           Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.client = client;
        this.writer = writer;
        this.subjects = subjects;
        this.words = words;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public static String key(String prefix, String value) {
        String k = prefix + value;
        return k.length() <= 150 ? k : k.substring(0, 150);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(Market.KZ.zone()));
    }

    /** Раз в сутки (7:15): «от» — прошлый удачный сбор минус 3 дня, впервые — 2 года назад. */
    public void collectDaily(ImportSummary sum) {
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = today();
        LocalDate from = writer.lookup(DAILY).filter(l -> l.getError() == null)
                .map(l -> l.getFetchedAt().atZoneSameInstant(Market.KZ.zone()).toLocalDate().minusDays(3))
                .orElse(today.minusYears(YEARS));
        List<String> list = words.get();
        int rows = 0, down = 0;
        for (String w : list) {
            try {
                rows += collectWord(w, from, today, null).rows();
                sum.setUpdated(sum.getUpdated() + 1);
                down = 0;
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) {
                    sum.addError("сбор остановлен: " + ErrorText.of(e));
                    break;
                }
                sum.addError("«" + w + "»: " + ErrorText.of(e));
                log.warn("Цены рынка: слово «{}»: {}", w, ErrorText.of(e));
                if (++down >= DOWN_STOP) break;
            }
        }
        if (sum.getErrors() == 0) writer.mark(DAILY, rows, null, now);
        sum.setFetched(list.size());
        sum.setCreated(rows);
        sum.setMessage("Цены рынка: слов " + list.size() + " с " + from.format(DATE) + ", строк договоров " + rows
                + (sum.getErrors() > 0 ? "; ошибок " + sum.getErrors() + ": " + sum.getLastError() : ""));
    }

    /** Живой запрос по слову (спека §9): свежее суток — без площадки; сбой — повтор не раньше 10 мин. */
    public Lookup ensureWord(String word, Duration budget) {
        String w = word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
        if (w.length() < 3) return new Lookup(false, null, "слово слишком короткое");
        return ensure(key("w:", w), () -> {
            LocalDate today = today();
            return collectWord(w, today.minusYears(YEARS), today, clock.instant().plus(budget));
        });
    }

    /** История заказчика (спека §8): его договоры за 2 года → строки набора; свежесть — сутки. */
    public Lookup ensureCustomer(String bin) {
        if (bin == null || !bin.matches("\\d{12}")) return new Lookup(false, null, "БИН заказчика неизвестен");
        return ensure(key("c:", bin), () -> {
            LocalDate today = today();
            List<ContractRow> rows = call(() -> client.contractsByCustomer(bin, today.minusYears(YEARS), today));
            List<Long> lotIds = rows.stream().flatMap(c -> c.units().stream()).map(ContractUnitRow::lotId).filter(Objects::nonNull)
                    .distinct().toList();
            List<ClosedLot> lots = new ArrayList<>();
            for (int i = 0; i < lotIds.size(); i += LOTS_BATCH) {
                List<Long> batch = lotIds.subList(i, Math.min(lotIds.size(), i + LOTS_BATCH));
                lots.addAll(call(() -> client.lotsByIds(batch)));
            }
            subjects.names(rows.stream().map(ContractRow::supplierBin).filter(Objects::nonNull).toList(), 30);
            return new Collected(writer.upsert(lots, rows, OffsetDateTime.now(clock)), true);
        });
    }

    private Lookup ensure(String key, Supplier<Collected> work) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<MarketLookup> prev = writer.lookup(key);
        if (prev.isPresent() && fresh(prev.get(), now)) {
            return new Lookup(prev.get().getError() == null, prev.get().getFetchedAt(), prev.get().getError());
        }
        OffsetDateTime before = prev.map(MarketLookup::getFetchedAt).orElse(null);
        if (!client.isConfigured()) return new Lookup(false, before, "токен goszakup не настроен");
        try {
            Collected c = work.get();
            writer.mark(key, c.rows(), c.complete() ? null : "собрано не всё — обновится при следующем открытии", now);
            return new Lookup(c.complete(), now, c.complete() ? null : "собрано не всё — обновится позже");
        } catch (RuntimeException e) {
            String err = "не удалось обновить: " + ErrorText.of(e);
            writer.mark(key, 0, err, now);
            log.warn("Цены рынка: {} — {}", key, ErrorText.of(e));
            return new Lookup(false, before, err);
        }
    }

    static boolean fresh(MarketLookup l, OffsetDateTime now) {
        return l.getFetchedAt().isAfter(now.minus(l.getError() == null ? FRESH : ERROR_BACKOFF));
    }

    /** Слово: состоявшиеся лоты (до 5 страниц) → договоры пачками по 100 объявлений → строки; deadline — не дольше. */
    Collected collectWord(String word, LocalDate from, LocalDate to, Instant deadline) {
        List<ClosedLot> lots = new ArrayList<>();
        Long after = null;
        boolean complete = true;
        for (int page = 0; page < WORD_PAGES; page++) {
            if (late(deadline)) { complete = false; break; }
            Long a = after;
            SearchPage<ClosedLot> p = call(() -> client.closedLots(word, from, to, a));
            lots.addAll(p.items());
            after = p.nextAfter();
            if (after == null) break;
        }
        List<Long> buys = lots.stream().map(ClosedLot::buyId).filter(Objects::nonNull).distinct().toList();
        int n = 0;
        for (int i = 0; i < buys.size(); i += CONTRACT_BATCH) {
            if (late(deadline)) { complete = false; break; }
            List<Long> batch = buys.subList(i, Math.min(buys.size(), i + CONTRACT_BATCH));
            List<ContractRow> rows = call(() -> client.contractsByBuyIds(batch));
            Set<Long> inBatch = new HashSet<>(batch);
            n += writer.upsert(lots.stream().filter(l -> inBatch.contains(l.buyId())).toList(), rows, OffsetDateTime.now(clock));
        }
        return new Collected(n, complete);
    }

    private boolean late(Instant deadline) {
        return deadline != null && clock.instant().isAfter(deadline);
    }

    private <T> T call(Supplier<T> s) {
        try {
            return UpstreamRetry.call(sleeper, s);
        } finally {
            try {
                sleeper.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
