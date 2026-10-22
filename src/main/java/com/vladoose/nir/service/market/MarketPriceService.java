package com.vladoose.nir.service.market;

import com.vladoose.nir.dto.response.MarketHint;
import com.vladoose.nir.dto.response.MarketLotSummary;
import com.vladoose.nir.dto.response.MarketPriceResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.MarketContractRepository;
import com.vladoose.nir.repository.TenderLotBidRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.market.MarketCategories.Category;
import com.vladoose.nir.service.tenderresults.ResultTexts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Цена рынка (спека 2026-10-22-tender-results-market-prices §9, §10): «такой же товар» — та же категория ЕНС ТРУ и та же единица,
 * 2 года. Панель (лот, текст) — с живым запросом слова, не обновлявшегося сутки (до 20 с); сводки у лотов и подсказки — только из
 * набора. Метод не транзакционный: сеть — вне транзакций, чтение — репозиториями.
 */
@Service
public class MarketPriceService implements MarketLines {

    static final Duration LIVE_BUDGET = Duration.ofSeconds(20);
    static final int ROWS = 100;
    static final int ALTERNATIVES = 5;
    static final int MAX_HINTS = 200;
    static final Duration TELEGRAM_BUDGET = Duration.ofSeconds(30);
    static final int TELEGRAM_WORDS = 15;
    private static final MarketCollector.Lookup NONE = new MarketCollector.Lookup(false, null, "не из чего искать");

    private final MarketCollector collector;
    private final MarketContractWriter writer;
    private final MarketContractRepository contracts;
    private final TenderLotRepository lots;
    private final TenderLotBidRepository bids;
    private final GoszakupSubjectRepository subjects;
    private final GoszakupResultsClient client;
    private final Clock clock;

    @Autowired
    public MarketPriceService(MarketCollector collector, MarketContractWriter writer, MarketContractRepository contracts,
                              TenderLotRepository lots, TenderLotBidRepository bids, GoszakupSubjectRepository subjects,
                              GoszakupResultsClient client) {
        this(collector, writer, contracts, lots, bids, subjects, client, Clock.systemUTC());
    }

    public MarketPriceService(MarketCollector collector, MarketContractWriter writer, MarketContractRepository contracts,
                              TenderLotRepository lots, TenderLotBidRepository bids, GoszakupSubjectRepository subjects,
                              GoszakupResultsClient client, Clock clock) {
        this.collector = collector;
        this.writer = writer;
        this.contracts = contracts;
        this.lots = lots;
        this.bids = bids;
        this.subjects = subjects;
        this.client = client;
        this.clock = clock;
    }

    private LocalDate from() {
        return LocalDate.now(clock.withZone(Market.KZ.zone())).minusYears(MarketCollector.YEARS);
    }

    /** У лота (гард рынка — у вызывающего): код площадки, нет — с площадки по id лота, нет и его — по названию. */
    public MarketPriceResponse forLot(long lotId, String code, String unit, String filter) {
        TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден"));
        Tender t = lot.getTender();
        String lotCode = lot.getEnstruCode();
        if (lotCode == null && t.getPlatform() == TenderPlatform.GOSZAKUP && lot.getExtLotId() != null && client.isConfigured()) {
            try {
                List<ClosedLot> found = client.lotsByIds(List.of(lot.getExtLotId()));
                if (!found.isEmpty()) {
                    writer.fillLot(lotId, found.get(0));
                    lotCode = found.get(0).enstruCode();
                }
            } catch (RuntimeException e) {
                // категория — по названию лота
            }
        }
        String kw = MarketCategories.keyword(lot.getEquipName());
        MarketCollector.Lookup l = kw == null ? NONE : collector.ensureWord(kw, LIVE_BUDGET);
        List<Category> cats = MarketCategories.relevant(lot.getEquipName(), categories(kw));
        String eff = code != null && !code.isBlank() ? code.strip() : lotCode;
        Category cat = eff != null ? category(eff, cats) : MarketCategories.pick(lot.getEquipName(), cats);
        BigDecimal budget = t.getSource() == Source.PUBLIC_TENDER ? lot.getMaxCost() : null;
        String lotUnit = lot.getUnit() != null && !lot.getUnit().isBlank() ? lot.getUnit() : lot.getPlanUnit();
        return build(cat, cats, unit, lotUnit, filter, budget, l);
    }

    /** По тексту (позиция заявки, строка КП, товар сайта). */
    public MarketPriceResponse forText(String text, String code, String unit, String filter) {
        String kw = MarketCategories.keyword(text);
        MarketCollector.Lookup l = kw == null ? NONE : collector.ensureWord(kw, LIVE_BUDGET);
        List<Category> cats = MarketCategories.relevant(text, categories(kw));
        Category cat = code != null && !code.isBlank() ? category(code.strip(), cats) : MarketCategories.pick(text, cats);
        return build(cat, cats, unit, null, filter, null, l);
    }

    /** Сводки «Рынок: …» у лотов карточки — только из набора (решение 10). */
    @Transactional(readOnly = true)
    public Map<Long, MarketLotSummary> lotSummaries(Collection<TenderLot> list) {
        Map<String, List<MarketContract>> byCode = new HashMap<>();
        Map<String, List<Category>> cats = new HashMap<>();
        Map<Long, MarketLotSummary> out = new HashMap<>();
        for (TenderLot l : list) {
            String code = l.getEnstruCode();
            if (code == null) {   // у лотов 2026 года площадка часто не отдаёт пункт плана — категория по названию (как в панели)
                String kw = MarketCategories.keyword(l.getEquipName());
                Category cat = kw == null ? null : MarketCategories.pick(l.getEquipName(), cats.computeIfAbsent(kw, this::categories));
                code = cat == null ? null : cat.code();
            }
            if (code == null) continue;
            List<MarketContract> rows = byCode.computeIfAbsent(code, c -> contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(c, from()));
            if (rows.isEmpty()) continue;
            String unit = effectiveUnit(null, l.getUnit() != null && !l.getUnit().isBlank() ? l.getUnit() : l.getPlanUnit(), units(rows));
            List<MarketContract> same = sameUnit(rows, unit);
            MarketStats st = MarketStats.of(prices(same), budgets(same), l.getMaxCost());
            if (st.count() > 0) out.put(l.getId(), new MarketLotSummary(MarketTexts.lotLine(st, unit), st.count(), st.median(), code));
        }
        return out;
    }

    /** Подсказки по текстам — только из набора, до 200 (окно «Запросить цены», строки КП, товары сайта). */
    @Transactional(readOnly = true)
    public List<MarketHint> hints(List<String> texts) {
        Map<String, List<Category>> cats = new HashMap<>();
        Map<String, List<MarketContract>> rowsByCode = new HashMap<>();
        List<MarketHint> out = new ArrayList<>();
        for (String text : texts.stream().limit(MAX_HINTS).toList()) {
            String kw = MarketCategories.keyword(text);
            Category cat = kw == null ? null : MarketCategories.pick(text, cats.computeIfAbsent(kw, this::categories));
            if (cat == null) {
                out.add(new MarketHint(text, null, 0, null, null));
                continue;
            }
            List<MarketContract> rows = rowsByCode.computeIfAbsent(cat.code(), c -> contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(c, from()));
            String unit = effectiveUnit(null, null, units(rows));
            List<MarketContract> same = sameUnit(rows, unit);
            MarketStats st = MarketStats.of(prices(same), budgets(same), null);
            out.add(new MarketHint(text, MarketTexts.hintLine(st), st.count(), st.median(), cat.code()));
        }
        return out;
    }

    @Override
    public Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender) {
        try {
            return telegramLines(lotIdsByTender, TELEGRAM_BUDGET);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * Строки для сообщений: по тендеру — первый лот с данными. Живой запрос — по новым словам, не больше 15 и не дольше budget на
     * всё (спека §10); время вышло — только набор. Код лота goszakup без кода — с площадки, пока есть время.
     */
    public Map<Long, String> telegramLines(Map<Long, List<Long>> lotIdsByTender, Duration budget) {
        Instant deadline = clock.instant().plus(budget);
        Set<Long> all = new HashSet<>();
        lotIdsByTender.values().forEach(all::addAll);
        if (all.isEmpty()) return Map.of();
        Map<Long, MarketContractWriter.LotInfo> info = new HashMap<>();
        for (MarketContractWriter.LotInfo i : writer.lotInfos(all)) info.put(i.lotId(), i);
        Set<String> asked = new HashSet<>();
        Map<String, List<MarketContract>> rowsByCode = new HashMap<>();
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Long>> e : lotIdsByTender.entrySet()) {
            for (Long lotId : e.getValue()) {
                MarketContractWriter.LotInfo i = info.get(lotId);
                if (i == null) continue;
                String line = telegramLine(i, deadline, asked, rowsByCode);
                if (line != null) {
                    out.put(e.getKey(), line);
                    break;
                }
            }
        }
        return out;
    }

    private String telegramLine(MarketContractWriter.LotInfo i, Instant deadline, Set<String> asked,
                                Map<String, List<MarketContract>> rowsByCode) {
        String code = i.code();
        if (code == null && i.goszakup() && i.extLotId() != null && client.isConfigured() && clock.instant().isBefore(deadline)) {
            try {
                List<ClosedLot> found = client.lotsByIds(List.of(i.extLotId()));
                if (!found.isEmpty()) {
                    writer.fillLot(i.lotId(), found.get(0));
                    code = found.get(0).enstruCode();
                }
            } catch (RuntimeException e) {
                // по названию
            }
        }
        String kw = MarketCategories.keyword(i.name());
        if (kw != null && asked.size() < TELEGRAM_WORDS && asked.add(kw)) {
            Duration left = Duration.between(clock.instant(), deadline);
            if (!left.isNegative() && !left.isZero()) collector.ensureWord(kw, left);
        }
        if (code == null) {
            Category cat = MarketCategories.pick(i.name(), categories(kw));
            code = cat == null ? null : cat.code();
        }
        if (code == null) return null;
        List<MarketContract> rows = rowsByCode.computeIfAbsent(code, c -> contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(c, from()));
        if (rows.isEmpty()) return null;
        String unit = effectiveUnit(null, i.unit(), units(rows));
        List<MarketContract> same = sameUnit(rows, unit);
        return MarketTexts.telegramLine(MarketStats.of(prices(same), budgets(same), i.budget()));
    }

    private List<Category> categories(String kw) {
        if (kw == null) return List.of();
        List<Category> out = new ArrayList<>();
        for (Object[] row : contracts.findCategories("%" + kw + "%", from())) {
            out.add(new Category((String) row[0], (String) row[1], (String) row[2], ((Number) row[3]).intValue()));
        }
        return out;
    }

    private Category category(String code, List<Category> cats) {
        return cats.stream().filter(c -> c.code().equals(code)).findFirst().orElseGet(() -> {
            List<MarketContract> rows = contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(code, from());
            return rows.isEmpty() ? new Category(code, null, null, 0)
                    : new Category(code, rows.get(0).getName(), rows.get(0).getDescription(), rows.size());
        });
    }

    private MarketPriceResponse build(Category cat, List<Category> cats, String unit, String lotUnit, String filter, BigDecimal budget,
                                      MarketCollector.Lookup l) {
        List<Category> alternatives = cats.stream().filter(c -> cat == null || !c.code().equals(cat.code())).limit(ALTERNATIVES).toList();
        if (cat == null) {
            return new MarketPriceResponse(null, alternatives, null, List.of(), MarketStats.of(List.of(), List.of(), budget),
                    new MarketPriceResponse.Bids(0, null), List.of(), l.fetchedAt(), !l.ok(), l.error());
        }
        List<MarketContract> rows = contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(cat.code(), from());
        List<String> units = units(rows);
        String eff = effectiveUnit(unit, lotUnit, units);
        List<MarketContract> same = sameUnit(rows, eff);
        String f = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        if (!f.isEmpty()) same = same.stream().filter(r -> text(r).contains(f)).toList();
        MarketStats st = MarketStats.of(prices(same), budgets(same), budget);
        Map<String, String> names = subjects.findByBinIn(same.stream().map(MarketContract::getSupplierBin).filter(Objects::nonNull)
                        .collect(Collectors.toSet())).stream().filter(s -> s.getNameRu() != null)
                .collect(Collectors.toMap(GoszakupSubject::getBin, GoszakupSubject::getNameRu, (a, b) -> a));
        List<MarketPriceResponse.Row> out = same.stream()
                .sorted(Comparator.comparing(MarketContract::getSignedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(ROWS)
                .map(r -> new MarketPriceResponse.Row(r.getSignedAt(), r.getRegion(), r.getCustomerName(), r.getQuantity(), eff,
                        r.getUnitPrice(), r.getBudgetUnitPrice(),
                        r.getSupplierBin() == null ? null : names.getOrDefault(r.getSupplierBin(), "БИН " + r.getSupplierBin()), r.getAnno(),
                        r.getName()))
                .toList();
        Category shown = new Category(cat.code(), cat.name() != null ? cat.name() : rows.isEmpty() ? null : rows.get(0).getName(),
                cat.description() != null ? cat.description() : rows.isEmpty() ? null : rows.get(0).getDescription(), rows.size());
        return new MarketPriceResponse(shown, alternatives, eff, units, st, bidStats(cat.code(), eff), out, l.fetchedAt(), !l.ok(), l.error());
    }

    private MarketPriceResponse.Bids bidStats(String code, String unit) {
        List<BigDecimal> prices = new ArrayList<>();
        for (Object[] row : bids.findBidPrices(code, from().atStartOfDay())) {
            if (norm((String) row[1]).equals(unit)) prices.add((BigDecimal) row[0]);
        }
        MarketStats st = MarketStats.of(prices, List.of(), null);
        return new MarketPriceResponse.Bids(st.count(), st.median());
    }

    /** Единицы категории по числу строк (больше — первой); строка без единицы — по {@link #unitOf}. */
    private static List<String> units(List<MarketContract> rows) {
        String sole = soleUnit(rows);
        Map<String, Long> by = rows.stream().map(r -> unitOf(r, sole)).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(u -> u, LinkedHashMap::new, Collectors.counting()));
        return by.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
    }

    private static List<MarketContract> sameUnit(List<MarketContract> rows, String unit) {
        String sole = soleUnit(rows);
        return rows.stream().filter(r -> unit.equals(unitOf(r, sole))).toList();
    }

    /**
     * Единица строки: своя; нет (лот 2026 года без пункта плана — живая проверка 2026-10-22) — единственная единица строк
     * категории с единицей; единицы разные или их нет вовсе — неизвестна: штуки с упаковками не смешиваем (спека §9).
     */
    private static String unitOf(MarketContract r, String sole) {
        return r.getUnit() != null && !r.getUnit().isBlank() ? norm(r.getUnit()) : sole;
    }

    private static String soleUnit(List<MarketContract> rows) {
        Set<String> known = rows.stream().map(MarketContract::getUnit).filter(u -> u != null && !u.isBlank())
                .map(MarketPriceService::norm).collect(Collectors.toSet());
        return known.size() == 1 ? known.iterator().next() : null;
    }

    /** Выбранная → единица лота → единица большинства строк (спека §9). */
    private static String effectiveUnit(String chosen, String lotUnit, List<String> units) {
        if (chosen != null && !chosen.isBlank()) return norm(chosen);
        if (lotUnit != null && !lotUnit.isBlank()) return norm(lotUnit);
        return units.isEmpty() ? "шт" : units.get(0);
    }

    static String norm(String unit) {
        return ResultTexts.unit(unit, null);
    }

    private static List<BigDecimal> prices(List<MarketContract> rows) {
        return rows.stream().map(MarketContract::getUnitPrice).toList();
    }

    private static List<BigDecimal> budgets(List<MarketContract> rows) {
        return rows.stream().map(MarketContract::getBudgetUnitPrice).filter(Objects::nonNull).toList();
    }

    private static String text(MarketContract m) {
        return (Objects.toString(m.getName(), "") + " " + Objects.toString(m.getDescription(), "") + " "
                + Objects.toString(m.getExtraDesc(), "")).toLowerCase(Locale.ROOT);
    }
}
