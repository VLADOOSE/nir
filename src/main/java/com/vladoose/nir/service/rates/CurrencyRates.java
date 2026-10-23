package com.vladoose.nir.service.rates;

import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.CurrencyRatesResponse;
import com.vladoose.nir.entity.CurrencyRate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.CurrencyRateRepository;
import com.vladoose.nir.repository.OfficialRateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Наш курс и курс сегодня (спека 2026-10-23 §4). Наш курс — от него наценка и цена клиенту; курс сегодня (НБ РК, только
 * KZ) — настоящая закупка и прибыль. Валюта рынка — курс 1, курса сегодня у неё нет.
 */
@Service
public class CurrencyRates {

    public static final String NBRK = "NBRK";
    static final int STALE_DAYS = 3;

    public record Today(BigDecimal rate, LocalDate date, boolean stale) {}

    private final CurrencyRateRepository ours;
    private final OfficialRateRepository official;
    private final boolean todayEnabled;
    private final Clock clock;

    @Autowired
    public CurrencyRates(CurrencyRateRepository ours, OfficialRateRepository official,
                         @Value("${rates.nbrk.enabled:false}") boolean todayEnabled) {
        this(ours, official, todayEnabled, Clock.systemUTC());
    }

    public CurrencyRates(CurrencyRateRepository ours, OfficialRateRepository official, boolean todayEnabled, Clock clock) {
        this.ours = ours;
        this.official = official;
        this.todayEnabled = todayEnabled;
        this.clock = clock;
    }

    /** Валюты нашего курса рынка (в таком порядке — на странице). */
    public static List<String> currencies(Market m) {
        return m == Market.KZ ? List.of("RUB", "USD", "EUR", "CNY") : List.of("USD", "EUR", "CNY", "KZT");
    }

    public Optional<BigDecimal> our(Market m, String currency) {
        if (m == null || currency == null) return Optional.empty();
        if (currency.equals(m.currencyCode())) return Optional.of(BigDecimal.ONE);
        return ours.findByMarketAndCurrency(m, currency).map(CurrencyRate::getOurRate);
    }

    public Map<String, BigDecimal> ours(Market m) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (CurrencyRate r : ours.findByMarket(m)) out.put(r.getCurrency(), r.getOurRate());
        return out;
    }

    public Optional<Today> today(Market m, String currency) {
        if (m != Market.KZ || currency == null || currency.equals(m.currencyCode())) return Optional.empty();
        LocalDate now = LocalDate.now(clock.withZone(m.zone()));
        return official.findFirstBySourceAndCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(NBRK, currency, now)
                .map(r -> new Today(r.getRate(), r.getRateDate(), r.getRateDate().isBefore(now.minusDays(STALE_DAYS))));
    }

    public Map<String, Today> today(Market m) {
        Map<String, Today> out = new LinkedHashMap<>();
        for (String c : currencies(m)) today(m, c).ifPresent(t -> out.put(c, t));
        return out;
    }

    @Transactional(readOnly = true)
    public CurrencyRatesResponse view(Market m) {
        Map<String, CurrencyRate> stored = new HashMap<>();
        for (CurrencyRate r : ours.findByMarket(m)) stored.put(r.getCurrency(), r);
        List<CurrencyRatesResponse.Row> rows = new ArrayList<>();
        for (String c : currencies(m)) {
            CurrencyRate r = stored.get(c);
            Today t = today(m, c).orElse(null);
            rows.add(new CurrencyRatesResponse.Row(c, r == null ? null : r.getOurRate(), r == null ? null : r.getUpdatedBy(),
                    r == null ? null : r.getUpdatedAt(), t == null ? null : new CurrencyRatesResponse.Today(t.rate(), t.date(), t.stale()),
                    RateMath.marginPct(r == null ? null : r.getOurRate(), t == null ? null : t.rate())));
        }
        return new CurrencyRatesResponse(m.name(), m.currencyCode(), todayEnabled && m == Market.KZ, rows);
    }

    /** Весь список: сначала проверка всего ввода, потом запись (спека §4.1); пустой курс — строка удаляется. */
    @Transactional
    public CurrencyRatesResponse save(Market m, CurrencyRatesRequest req, String user) {
        Map<String, BigDecimal> wanted = new LinkedHashMap<>();
        for (CurrencyRatesRequest.Entry e : req == null || req.rates() == null ? List.<CurrencyRatesRequest.Entry>of() : req.rates()) {
            if (e == null || e.currency() == null || e.currency().isBlank()) throw new BadRequestException("Не указана валюта");
            String c = e.currency().trim().toUpperCase(Locale.ROOT);
            if (!currencies(m).contains(c)) throw new BadRequestException("Валюта «" + c + "» — не для этого рынка");
            if (wanted.containsKey(c)) throw new BadRequestException("Валюта " + c + " — дважды");
            wanted.put(c, RateMath.check(e.ourRate()));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Map.Entry<String, BigDecimal> w : wanted.entrySet()) {
            Optional<CurrencyRate> row = ours.findByMarketAndCurrency(m, w.getKey());
            if (w.getValue() == null) {
                row.ifPresent(ours::delete);
                continue;
            }
            CurrencyRate r = row.orElseGet(() -> CurrencyRate.builder().market(m).currency(w.getKey()).build());
            r.setOurRate(w.getValue());
            r.setUpdatedBy(user);
            r.setUpdatedAt(now);
            ours.save(r);
        }
        ours.flush();
        return view(m);
    }
}
