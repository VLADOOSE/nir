package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.lotmodel.LotModelSearch;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.service.rates.RateMath;
import com.vladoose.nir.util.BrandMatch;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Подсказки «у кого и почём» (спека 2026-10-23 §6–7): строки действующих прайсов по ключу товара — у каждого поставщика его
 * самый новый прайс с этим товаром (все комплектации); лучшая — самая дешёвая закупка в валюте рынка по нашему курсу.
 * Только из базы, без сети.
 */
@Service
public class PriceHints {

    public static final int ROWS_MAX = 10;
    public static final int TEXTS_MAX = 200;
    public static final int SEARCH_MAX = 50;
    static final int HISTORY_MAX = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<Long> NO_IDS = List.of(-1L);
    private static final List<String> NO_KEYS = List.of("");

    /** Что ищем: текст позиции (коды моделей), наш товар, позиция каталога; preferBrand — сначала завод этого бренда. */
    public record Target(String text, Long siteProductId, Long equipmentId, String preferBrand) {
        public static Target text(String text) {
            return new Target(text, null, null, null);
        }
    }

    private record Rates(Map<String, BigDecimal> our, Map<String, CurrencyRates.Today> today, LocalDate date) {}

    private record Entry(String kind, LocalDate date, String supplier, String currency, BigDecimal price, Long priceListId,
                         Long priceRequestId) {}

    private final PriceListItemRepository items;
    private final PriceRequestItemRepository replies;
    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;
    private final MedRegistryRepository registry;
    private final CurrencyRates rates;
    private final LotModelSearch modelSearch;
    private final MedEquipmentService equipmentService;

    public PriceHints(PriceListItemRepository items, PriceRequestItemRepository replies, SiteProductRepository products,
                      MedEquipmentRepository equipment, MedRegistryRepository registry, CurrencyRates rates,
                      LotModelSearch modelSearch, MedEquipmentService equipmentService) {
        this.items = items;
        this.replies = replies;
        this.products = products;
        this.equipment = equipment;
        this.registry = registry;
        this.rates = rates;
        this.modelSearch = modelSearch;
        this.equipmentService = equipmentService;
    }

    @Transactional(readOnly = true)
    public PriceHint hint(Market m, Target t) {
        return hints(m, List.of(t)).get(0);
    }

    /** Подсказки для набора целей — одним запросом строк. */
    @Transactional(readOnly = true)
    public List<PriceHint> hints(Market m, List<Target> targets) {
        if (targets.isEmpty()) return List.of();
        Map<Long, SiteProduct> sp = new HashMap<>();
        products.findAllById(targets.stream().map(Target::siteProductId).filter(Objects::nonNull).toList()).forEach(p -> sp.put(p.getId(), p));
        Map<Long, MedEquipment> eq = new HashMap<>();
        equipment.findAllById(targets.stream().map(Target::equipmentId).filter(Objects::nonNull).toList()).forEach(e -> eq.put(e.getId(), e));
        List<List<String>> keys = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>();
        for (Target t : targets) {
            List<String> k = keys(t, sp, eq);
            keys.add(k);
            all.addAll(k);
        }
        List<PriceListItem> rows = all.isEmpty() ? List.of() : find(m, all);
        Rates r = rates(m);
        Map<String, MedRegistry> regs = regs(rows);
        List<PriceHint> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            List<String> k = keys.get(i);
            List<PriceListItem> mine = k.isEmpty() ? List.of()
                    : rows.stream().filter(x -> PriceListKeys.same(PriceListKeys.of(x), k)).toList();
            out.add(new PriceHint(targets.get(i).text(), current(mine, targets.get(i).preferBrand(), m, r, regs)));
        }
        return out;
    }

    /** Поиск по действующим прайсам (спека §6.2 п. 1): каждое слово от 2 символов; старые прайсы, вытесненные новыми, — мимо. */
    @Transactional(readOnly = true)
    public List<PriceHintRow> search(Market m, String q, int limit) {
        List<String> words = words(q);
        if (words.isEmpty()) return List.of();
        int max = Math.max(1, Math.min(limit, SEARCH_MAX));
        List<PriceListItem> hit = items.findActiveByWord(m, like(words.get(0)), PageRequest.of(0, 500)).stream()
                .filter(x -> words.stream().allMatch(hay(x)::contains)).toList();
        Set<String> keys = new LinkedHashSet<>();
        hit.forEach(x -> keys.addAll(PriceListKeys.of(x)));
        List<PriceListItem> same = keys.isEmpty() ? List.of() : find(m, keys);
        hit = hit.stream().filter(x -> same.stream().noneMatch(y -> newerOfSameSupplier(y, x))).toList();
        Rates r = rates(m);
        Map<String, MedRegistry> regs = regs(hit);
        return hit.stream()
                .sorted(Comparator.comparing((PriceListItem x) -> x.getPriceList().getDistributor().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PriceListItem::getPriceList, PriceListOrder.NEWER).thenComparingInt(PriceListItem::getLineNo))
                .limit(max).map(x -> row(x, m, r, regs)).toList();
    }

    /** История цен товара строки (спека §7): строки прайсов и ответы на запросы цен, новые сверху, до 10. */
    @Transactional(readOnly = true)
    public List<PriceHistoryEntry> history(Long rowId) {
        Market m = MarketContext.get();
        PriceListItem r = items.findById(rowId).orElse(null);
        if (r == null || r.getPriceList().getMarket() != m) throw new NotFoundException("Строка прайса не найдена: " + rowId);
        List<String> keys = PriceListKeys.of(r);
        if (keys.isEmpty()) return List.of();
        List<Entry> all = new ArrayList<>();
        Map<Long, PriceListItem> perList = new LinkedHashMap<>();
        for (PriceListItem x : find(m, new LinkedHashSet<>(keys))) {
            if (!PriceListKeys.same(PriceListKeys.of(x), keys)) continue;
            // в прайсе несколько комплектаций с этим ключом — та же, что у строки, иначе первая
            perList.merge(x.getPriceList().getId(), x, (a, b) -> sameName(b, r) && !sameName(a, r) ? b
                    : !sameName(a, r) && !sameName(b, r) && b.getLineNo() < a.getLineNo() ? b : a);
        }
        for (PriceListItem x : perList.values()) {
            PriceList p = x.getPriceList();
            all.add(new Entry("LIST", PriceListOrder.date(p), p.getDistributor().getName(), currency(p, m), x.getOurPrice(), p.getId(), null));
        }
        for (Object[] a : replies.findPricedForHistory(m.name())) {
            List<String> k = new ArrayList<>(PriceListKeys.of(longOrNull(a[9]), longOrNull(a[8]), null));
            PriceListCodes.keys(a[6] + " " + (a[7] == null ? "" : a[7])).forEach(c -> k.add("k" + c));
            if (!PriceListKeys.same(k, keys)) continue;
            all.add(new Entry("REPLY", date(a[5]), (String) a[2], a[4] == null ? m.currencyCode() : (String) a[4],
                    (BigDecimal) a[3], null, ((Number) a[1]).longValue()));
        }
        all.sort(Comparator.comparing(Entry::date, Comparator.nullsLast(Comparator.reverseOrder())));
        List<PriceHistoryEntry> out = new ArrayList<>();
        for (int i = 0; i < all.size() && out.size() < HISTORY_MAX; i++) {
            Entry e = all.get(i);
            BigDecimal pct = null;
            for (int j = i + 1; j < all.size(); j++) {
                Entry o = all.get(j);
                if (!o.supplier().equals(e.supplier()) || !o.currency().equals(e.currency())) continue;
                if (o.price().signum() != 0) pct = e.price().subtract(o.price()).multiply(HUNDRED).divide(o.price(), 1, RoundingMode.HALF_UP);
                break;
            }
            out.add(new PriceHistoryEntry(e.kind(), e.date(), e.supplier(), e.currency(), e.price(), pct, e.priceListId(), e.priceRequestId()));
        }
        return out;
    }

    /** Строка того же товара в самом новом действующем прайсе того же поставщика (та же комплектация, иначе первая). */
    @Transactional(readOnly = true)
    public Optional<PriceHintRow> latestOfSameSupplier(Market m, Long rowId) {
        PriceListItem r = items.findById(rowId).orElse(null);
        if (r == null || r.getPriceList().getMarket() != m) return Optional.empty();
        List<String> keys = PriceListKeys.of(r);
        if (keys.isEmpty()) return Optional.empty();
        Long supplier = r.getPriceList().getDistributor().getId();
        List<PriceListItem> same = find(m, new LinkedHashSet<>(keys)).stream()
                .filter(x -> x.getPriceList().getDistributor().getId().equals(supplier) && PriceListKeys.same(PriceListKeys.of(x), keys))
                .toList();
        if (same.isEmpty()) return Optional.empty();
        PriceList newest = same.stream().map(PriceListItem::getPriceList).sorted(PriceListOrder.NEWER).findFirst().orElseThrow();
        PriceListItem pick = same.stream().filter(x -> x.getPriceList().getId().equals(newest.getId()))
                .min(Comparator.comparing((PriceListItem x) -> !sameName(x, r)).thenComparingInt(PriceListItem::getLineNo))
                .orElseThrow();
        return Optional.of(row(pick, m, rates(m), regs(List.of(pick))));
    }

    /** Кандидаты связи строки прайса (спека §5.3): наши товары (только KZ) и позиции каталога рынка. */
    @Transactional(readOnly = true)
    public PriceLinkCandidates linkCandidates(String q) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2) return new PriceLinkCandidates(List.of(), List.of());
        if (query.length() > 200) query = query.substring(0, 200);
        List<LotModelSearchResponse.Our> ours = MarketContext.get() == Market.KZ ? modelSearch.ours(query) : List.of();
        List<EquipmentSearchItemResponse> eq = equipmentService.search(query, 10).stream()
                .map(e -> new EquipmentSearchItemResponse(e.getId(), e.getName(), e.getManufact(),
                        e.getRegistration() == null ? null : PrivateRequestViewBuilder.candidate(e.getRegistration())))
                .toList();
        return new PriceLinkCandidates(ours, eq);
    }

    // ---- внутреннее ----

    /** Ключи цели: наш товар и его коды, позиция каталога, её наш товар и коды, коды текста. */
    private static List<String> keys(Target t, Map<Long, SiteProduct> sp, Map<Long, MedEquipment> eq) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (t.siteProductId() != null) {
            out.add("p" + t.siteProductId());
            SiteProduct p = sp.get(t.siteProductId());
            if (p != null) PriceListCodes.keys(p.getName()).forEach(k -> out.add("k" + k));
        }
        if (t.equipmentId() != null) {
            out.add("e" + t.equipmentId());
            MedEquipment e = eq.get(t.equipmentId());
            if (e != null) {
                if (e.getSiteProduct() != null) out.add("p" + e.getSiteProduct().getId());
                PriceListCodes.keys(e.getName()).forEach(k -> out.add("k" + k));
            }
        }
        PriceListCodes.keys(t.text()).forEach(k -> out.add("k" + k));
        return new ArrayList<>(out);
    }

    private List<PriceListItem> find(Market m, Collection<String> keys) {
        List<Long> p = new ArrayList<>(), e = new ArrayList<>();
        List<String> k = new ArrayList<>();
        for (String s : keys) {
            switch (s.charAt(0)) {
                case 'p' -> p.add(Long.valueOf(s.substring(1)));
                case 'e' -> e.add(Long.valueOf(s.substring(1)));
                default -> k.add(s.substring(1));
            }
        }
        return items.findActiveByKeys(m, p.isEmpty() ? NO_IDS : p, e.isEmpty() ? NO_IDS : e, k.isEmpty() ? NO_KEYS : k);
    }

    /** У каждого поставщика — строки его самого нового прайса среди найденных; порядок — §6.1. */
    private List<PriceHintRow> current(List<PriceListItem> rows, String preferBrand, Market m, Rates r, Map<String, MedRegistry> regs) {
        Map<Long, PriceList> newest = new HashMap<>();
        for (PriceListItem x : rows) {
            newest.merge(x.getPriceList().getDistributor().getId(), x.getPriceList(),
                    (a, b) -> PriceListOrder.NEWER.compare(a, b) <= 0 ? a : b);
        }
        Comparator<PriceListItem> order = Comparator
                .comparing((PriceListItem x) -> converted(x, m, r), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(x -> x.getPriceList().getDistributor().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(PriceListItem::getLineNo);
        if (preferBrand != null && !preferBrand.isBlank()) {
            order = Comparator.comparing((PriceListItem x) -> !maker(x.getPriceList().getDistributor(), preferBrand)).thenComparing(order);
        }
        return rows.stream()
                .filter(x -> newest.get(x.getPriceList().getDistributor().getId()).getId().equals(x.getPriceList().getId()))
                .sorted(order).limit(ROWS_MAX).map(x -> row(x, m, r, regs)).toList();
    }

    private static boolean maker(Distributor d, String brand) {
        return d.isManufacturer() && BrandMatch.firstCarried(d.getBrands(), brand) != null;
    }

    private static boolean newerOfSameSupplier(PriceListItem y, PriceListItem x) {
        return y.getPriceList().getDistributor().getId().equals(x.getPriceList().getDistributor().getId())
                && !y.getPriceList().getId().equals(x.getPriceList().getId())
                && PriceListOrder.NEWER.compare(y.getPriceList(), x.getPriceList()) < 0
                && PriceListKeys.same(PriceListKeys.of(y), PriceListKeys.of(x));
    }

    private PriceHintRow row(PriceListItem x, Market m, Rates r, Map<String, MedRegistry> regs) {
        PriceList p = x.getPriceList();
        String cur = currency(p, m);
        boolean foreign = !cur.equals(m.currencyCode());
        BigDecimal our = foreign ? r.our().get(cur) : null;
        CurrencyRates.Today td = foreign ? r.today().get(cur) : null;
        MedRegistry rec = x.getRegNumber() == null ? null : regs.get(RegNumbers.normalize(x.getRegNumber()));
        return new PriceHintRow(x.getId(), p.getId(), p.getDistributor().getId(), p.getDistributor().getName(),
                p.getDistributor().isManufacturer(), x.getSection(), x.getName(), x.getModel(), x.getArticle(),
                PriceListOrder.date(p), p.getValidTo(), p.getValidTo() != null && p.getValidTo().isBefore(r.date()), cur,
                x.getOurPrice(), our, converted(x, m, r), td == null ? null : RateMath.convert(x.getOurPrice(), td.rate()),
                td == null ? null : td.date(), p.getTerms(), x.getRegText(), rec != null ? rec.getRegNumber() : x.getRegNumber(),
                PriceListRegs.status(x.isNoReg(), x.getRegNumber(), rec, r.date()), rec == null ? null : rec.getExpirationDate());
    }

    private static BigDecimal converted(PriceListItem x, Market m, Rates r) {
        String cur = currency(x.getPriceList(), m);
        if (cur.equals(m.currencyCode())) return x.getOurPrice();
        BigDecimal our = r.our().get(cur);
        return our == null ? null : RateMath.convert(x.getOurPrice(), our);
    }

    static String currency(PriceList p, Market m) {
        return p.getCurrency() == null ? m.currencyCode() : p.getCurrency();
    }

    private Rates rates(Market m) {
        return new Rates(rates.ours(m), rates.today(m), LocalDate.now(m.zone()));
    }

    /** Записи реестра для № РУ строк — одним запросом (findByNormalizedRegNumber — полный проход по реестру на номер). */
    private Map<String, MedRegistry> regs(Collection<PriceListItem> rows) {
        Map<String, MedRegistry> out = new HashMap<>();
        Set<String> numbers = new HashSet<>();
        for (PriceListItem x : rows) if (x.getRegNumber() != null) numbers.add(RegNumbers.normalize(x.getRegNumber()));
        if (numbers.isEmpty()) return out;
        for (MedRegistry rec : registry.findByNormalizedRegNumberIn(numbers)) out.putIfAbsent(RegNumbers.normalize(rec.getRegNumber()), rec);
        return out;
    }

    static List<String> words(String q) {
        if (q == null) return List.of();
        String t = q.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
        if (t.length() > 200) t = t.substring(0, 200);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String w : t.split("\\s+")) if (w.length() >= 2) out.add(w);
        return out.stream().sorted(Comparator.comparingInt(String::length).reversed()).limit(5).toList();
    }

    private static String hay(PriceListItem x) {
        return String.join(" ", nz(x.getName()), nz(x.getModel()), nz(x.getArticle()), nz(x.getPriceList().getDistributor().getName()))
                .toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String like(String word) {
        return "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static boolean sameName(PriceListItem a, PriceListItem r) {
        return a.getName() != null && a.getName().equalsIgnoreCase(r.getName());
    }

    private static Long longOrNull(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static LocalDate date(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
