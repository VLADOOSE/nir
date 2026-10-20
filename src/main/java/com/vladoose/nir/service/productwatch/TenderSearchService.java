package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.TenderSearchHit;
import com.vladoose.nir.dto.response.TenderSearchStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.util.ErrorText;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.TechSpecExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * «Найти тендеры» в частной заявке (спека 2026-10-19-product-watch §11): слова позиций → лоты, объявления и планы
 * по всей стране за 90 дней → похожесть по словам → у лучших — сверка с ТЗ. Задача на заявку в своём однопоточном
 * исполнителе (токен общий с продом — параллельно не ходим); результат — в памяти 1 ч, в базу не пишется.
 */
@Service
public class TenderSearchService {

    private static final Logger log = LoggerFactory.getLogger(TenderSearchService.class);

    static final int MAX_POSITIONS = 10;
    static final int MAX_WORDS = 6;
    static final int WINDOW_DAYS = 90;
    static final int PAGE_FULL = 200;
    static final int MAX_ANNOUNCEMENTS = 3;
    static final int SPEC_TOP = 10;
    static final int MAX_HITS = 15;
    static final double MIN_SIMILARITY = 0.35;
    static final long PAUSE_MS = 300;
    static final Duration KEEP = Duration.ofHours(1);
    /** Общие слова названий: по ним искать бессмысленно (спека §11.1 п. 1). */
    static final Set<String> GENERIC_WORDS = Set.of("аппарат", "аппарата", "аппараты", "система", "системы", "набор", "набора",
            "комплект", "комплекта", "медицинский", "медицинская", "медицинское", "медицинские", "изделие", "изделия",
            "прибор", "прибора", "устройство", "оборудование", "исполнение");
    private static final List<String> STATE_ORDER = List.of("OPEN", "PLANNED", "FAILED", "CLOSED", "DONE", "CANCELLED");

    record Position(int lineNo, String name, String brand) {}

    record Input(long requestId, List<Position> positions) {}

    /** Поиск заявки: пишет только поток задачи, читает HTTP-поток опроса. */
    static final class Job {
        final Input input;
        final Instant createdAt;
        /** Встал в очередь за поиском другой заявки (исполнитель один — токен общий с продом). */
        volatile boolean behind;
        volatile boolean started;
        volatile boolean done;
        volatile int progress;
        volatile int total;
        volatile String error;
        volatile List<TenderSearchHit> hits = List.of();

        Job(Input input, Instant createdAt) { this.input = input; this.createdAt = createdAt; }

        TenderSearchStatus status() {
            String waiting = behind && !started && !done ? "ждёт: идёт поиск по другой заявке" : null;
            return new TenderSearchStatus(!done, progress, total, createdAt, waiting, error, hits);
        }
    }

    /** Кандидат до выдачи. */
    static final class Cand {
        String kind, anno, state;
        FoundLot head;
        FoundPlanPoint plan;
        double score;
        TreeSet<Integer> positions = new TreeSet<>();
        List<Integer> specPositions = List.of();
    }

    private final GoszakupSearchClient search;
    private final GoszakupClient client;
    private final PrivateRequestService requests;
    private final CatalogSource catalog;
    private final TenderRepository tenders;
    private final RegionResolver regions;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tender-search");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public TenderSearchService(GoszakupSearchClient search, GoszakupClient client, PrivateRequestService requests,
                               ProductCatalog catalog, TenderRepository tenders, RegionResolver regions) {
        this(search, client, requests, catalog, tenders, regions, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public TenderSearchService(GoszakupSearchClient search, GoszakupClient client, PrivateRequestService requests,
                               CatalogSource catalog, TenderRepository tenders, RegionResolver regions, Clock clock,
                               UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.client = client; this.requests = requests; this.catalog = catalog;
        this.tenders = tenders; this.regions = regions; this.clock = clock; this.sleeper = sleeper;
    }

    /** «Найти тендеры по стране»: идёт поиск этой заявки — его статус, иначе новая задача. */
    public TenderSearchStatus start(long requestId) {
        Input in = input(requestId);
        purge();
        Job old = jobs.get(requestId);
        if (old != null && !old.done) return old.status();
        Job job = new Job(in, clock.instant());
        job.behind = jobs.values().stream().anyMatch(j -> !j.done);
        jobs.put(requestId, job);
        executor.execute(() -> {
            job.started = true;
            MarketContext.set(Market.KZ);   // §6: фоновый поток, рынок — явно
            try {
                run(job);
            } catch (RuntimeException e) {
                log.warn("Найти тендеры: поиск заявки {} упал: {}", in.requestId(), e.toString());
                job.error = "Поиск прерван: " + ErrorText.of(e);
            } catch (Error e) {
                job.error = "Поиск прерван: " + ErrorText.of(e);
                throw e;
            } finally {
                job.done = true;
                MarketContext.clear();
            }
        });
        return job.status();
    }

    public TenderSearchStatus status(long requestId) {
        input(requestId);   // гард рынка и вида заявки
        Job j = jobs.get(requestId);
        return j == null ? new TenderSearchStatus(false, 0, 0, null, null, null, List.of()) : j.status();
    }

    /** Синхронный прогон — тот же код, что у задачи исполнителя (тесты). */
    public TenderSearchStatus runNow(long requestId) {
        Job job = new Job(input(requestId), clock.instant());
        job.started = true;
        try {
            run(job);
        } finally {
            job.done = true;
        }
        return job.status();
    }

    Input input(long requestId) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Поиск тендеров по стране — только для Казахстана");
        if (!search.isConfigured()) throw new BadRequestException("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
        Tender t = requests.findById(requestId);
        List<Position> ps = t.getLots().stream()
                .filter(l -> l.getEquipName() != null && !l.getEquipName().isBlank())
                .sorted(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber()))
                .limit(MAX_POSITIONS)
                .map(l -> new Position(l.getLotNumber() == null ? 0 : l.getLotNumber(), l.getEquipName(), l.getManufact()))
                .toList();
        if (ps.isEmpty()) throw new BadRequestException("В заявке нет позиций");
        return new Input(requestId, ps);
    }

    private void purge() {
        Instant edge = clock.instant().minus(KEEP);
        jobs.values().removeIf(j -> j.done && j.createdAt.isBefore(edge));
    }

    void run(Job job) {
        CatalogSnapshot snap = catalog.get();
        List<Position> ps = job.input.positions();
        LinkedHashSet<String> words = new LinkedHashSet<>();
        // планы — только по словам наших видов и только пункты нашего вида: по «запасным» словам позиций без группы
        // («фильтр», «сменный») план страны — фильтры для масок и автомобилей (живая проверка 2026-10-20)
        Set<String> typeWords = new HashSet<>();
        List<GroupRule> types = new ArrayList<>();
        for (Position p : ps) {
            GroupRule g = groupOf(p, snap);
            if (g != null && !types.contains(g)) types.add(g);
            for (String w : wordsOf(p, snap)) {
                if (words.size() < MAX_WORDS) words.add(w);
                if (g != null && words.contains(w)) typeWords.add(w);
            }
        }
        if (words.isEmpty()) {
            job.error = "Не по чему искать: в позициях одни общие слова";
            return;
        }
        job.total = words.size();
        ZonedDateTime kz = ZonedDateTime.now(clock.withZone(Market.KZ.zone()));
        Map<Long, FoundLot> lots = new LinkedHashMap<>();
        Map<Long, FoundPlanPoint> plans = new LinkedHashMap<>();
        try {
            for (String w : words) {
                collect(w, kz.toLocalDate(), lots, plans, typeWords.contains(w));
                job.progress++;
            }
            job.hits = rank(ps, lots.values(), plans.values(), kz.toLocalDateTime(), types);
        } catch (RuntimeException e) {
            job.error = (ProductWatchService.stopping(e) ? "Поиск остановлен: " : "Поиск прерван: ") + ErrorText.of(e);
        }
    }

    /** Группа нашего вида, которую позиция проходит по названию и бренду (бренды — не в счёт), или null. */
    static GroupRule groupOf(Position p, CatalogSnapshot snap) {
        String text = ProductText.normalize(p.name() + " " + Objects.toString(p.brand(), ""));
        for (GroupRule r : snap.groups()) {
            if (r.brandGroup()) continue;   // брендов в названиях лотов почти нет — по ним ищет поиск по стране
            if (r.typeWord(text, "", false) != null) return r;
        }
        return null;
    }

    /** Слова позиции: по которому она прошла группу и первое слово группы; без группы — два длинных не общих. */
    static List<String> wordsOf(Position p, CatalogSnapshot snap) {
        String text = ProductText.normalize(p.name() + " " + Objects.toString(p.brand(), ""));
        GroupRule r = groupOf(p, snap);
        if (r != null) {
            String w = r.typeWord(text, "", false);
            List<String> out = new ArrayList<>(List.of(ProductText.normalize(w).strip()));
            if (!r.searchWords().isEmpty()) {
                String first = ProductText.normalize(r.searchWords().get(0)).strip();
                if (!first.equals(out.get(0))) out.add(first);
            }
            return out;
        }
        return ProductText.words(text).stream()
                .filter(x -> x.length() > 4 && !GENERIC_WORDS.contains(x) && x.chars().anyMatch(Character::isLetter))
                .distinct().sorted(Comparator.comparingInt(String::length).reversed()).limit(2).toList();
    }

    private void collect(String w, LocalDate today, Map<Long, FoundLot> lots, Map<Long, FoundPlanPoint> plans, boolean plansToo) {
        LocalDate from = today.minusDays(WINDOW_DAYS), to = today.plusDays(1);
        SearchPage<FoundLot> p = call(() -> search.searchLots(w, null, from, to, null));
        p.items().forEach(l -> lots.putIfAbsent(l.id(), l));
        if (p.nextAfter() != null && p.items().size() >= PAGE_FULL) {
            Long after = p.nextAfter();
            call(() -> search.searchLots(w, null, from, to, after)).items().forEach(l -> lots.putIfAbsent(l.id(), l));
        }
        Set<String> covered = lots.values().stream().map(FoundLot::trdBuyNumberAnno).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        int n = 0;
        for (FoundAnnouncement a : call(() -> search.searchAnnouncements(w, null, from, to, null)).items()) {
            if (n >= MAX_ANNOUNCEMENTS) break;
            if (a.numberAnno() == null || covered.contains(a.numberAnno())) continue;
            n++;
            for (FoundLot l : call(() -> search.lotsOfAnnouncement(a.numberAnno()))) {
                lots.putIfAbsent(l.id(), l.announcement() != null ? l : withAnnouncement(l, a));
            }
        }
        if (!plansToo) return;
        int year = today.getYear();
        call(() -> search.searchPlans(w, List.of(year, year + 1), ProcurementPlanService.APPROVED, null)).items()
                .forEach(pp -> plans.putIfAbsent(pp.id(), pp));
    }

    static FoundLot withAnnouncement(FoundLot l, FoundAnnouncement a) {
        return new FoundLot(l.id(), l.lotNumber(), l.nameRu(), l.descriptionRu(), l.count(), l.amount(), l.refLotStatusId(),
                l.lastUpdateDate(), l.customerBin(), l.customerNameRu(), a.numberAnno(), a);
    }

    List<TenderSearchHit> rank(List<Position> ps, Collection<FoundLot> lots, Collection<FoundPlanPoint> plans, LocalDateTime now,
                               List<GroupRule> types) {
        Map<String, List<FoundLot>> byAnno = new LinkedHashMap<>();
        for (FoundLot l : lots) {
            if (l.trdBuyNumberAnno() != null) byAnno.computeIfAbsent(l.trdBuyNumberAnno(), k -> new ArrayList<>()).add(l);
        }
        List<Cand> tenderCands = new ArrayList<>();
        for (Map.Entry<String, List<FoundLot>> e : byAnno.entrySet()) {
            Cand c = new Cand();
            c.kind = "TENDER";
            c.anno = e.getKey();
            for (FoundLot l : e.getValue()) {
                String ann = l.announcement() == null ? "" : Objects.toString(l.announcement().nameRu(), "");
                Set<String> text = prefixSet(String.join(" ", Objects.toString(l.nameRu(), ""), Objects.toString(l.descriptionRu(), ""), ann));
                for (Position p : ps) {
                    double s = similarity(p, text);
                    if (s < MIN_SIMILARITY) continue;
                    c.positions.add(p.lineNo());
                    if (s > c.score) { c.score = s; c.head = l; }
                }
            }
            if (c.head == null) continue;
            FoundAnnouncement a = c.head.announcement();
            c.state = state(c.head.refLotStatusId(), a == null ? null : a.endDate(), now);
            tenderCands.add(c);
        }
        tenderCands.sort(Comparator.comparingDouble((Cand c) -> c.score).reversed());
        for (Cand c : tenderCands.subList(0, Math.min(SPEC_TOP, tenderCands.size()))) {
            String spec = spec(c.anno, c.head);
            if (spec != null) c.specPositions = specPositions(ps, spec);
        }
        List<Cand> all = new ArrayList<>(tenderCands);
        for (FoundPlanPoint pp : plans) {
            Cand c = new Cand();
            c.kind = "PLAN";
            c.plan = pp;
            c.state = "PLANNED";
            String planText = String.join(" ", Objects.toString(pp.nameRu(), ""), Objects.toString(pp.descRu(), ""),
                    Objects.toString(pp.extraDescRu(), ""));
            String norm = ProductText.normalize(planText);
            if (types.stream().noneMatch(g -> g.typeWord(norm, "", false) != null)) continue;   // не наш вид
            Set<String> text = prefixSet(planText);
            for (Position p : ps) {
                double s = similarity(p, text);
                if (s < MIN_SIMILARITY) continue;
                c.positions.add(p.lineNo());
                c.score = Math.max(c.score, s);
            }
            if (!c.positions.isEmpty()) all.add(c);
        }
        all.sort(Comparator.comparingInt((Cand c) -> c.specPositions.size()).reversed()
                .thenComparing(Comparator.comparingDouble((Cand c) -> c.score).reversed())
                .thenComparingInt(c -> STATE_ORDER.indexOf(c.state)));
        List<Cand> top = all.subList(0, Math.min(MAX_HITS, all.size()));
        Map<String, Long> inAis = tenders.findBySourceExtIdIn(top.stream().filter(c -> c.anno != null).map(c -> c.anno).toList())
                .stream().collect(Collectors.toMap(Tender::getSourceExtId, Tender::getId, (x, y) -> x));
        return top.stream().map(c -> hit(c, inAis.get(c.anno))).toList();
    }

    private TenderSearchHit hit(Cand c, Long tenderId) {
        List<Integer> pos = List.copyOf(c.positions);
        double score = Math.round(c.score * 100) / 100.0;
        if (c.plan != null) {
            FoundPlanPoint p = c.plan;
            return new TenderSearchHit("PLAN", null, null, p.nameRu(), p.descRu(), p.subjectNameRu(),
                    regions.resolve(p.deliveryPlace(), p.subjectNameRu()), p.count(), p.price(), p.amount(), null, "PLANNED",
                    p.tradeMethod(), score, pos, List.of(), null, p.year(), p.month());
        }
        FoundLot l = c.head;
        FoundAnnouncement a = l.announcement();
        String customer = a != null && a.orgNameRu() != null ? a.orgNameRu() : l.customerNameRu();
        BigDecimal unit = l.amount() != null && l.count() != null && l.count().signum() > 0
                ? l.amount().divide(l.count(), 2, RoundingMode.HALF_UP) : null;
        return new TenderSearchHit("TENDER", c.anno, l.lotNumber(), l.nameRu(), a == null ? null : a.nameRu(), customer,
                regions.resolve(customer), l.count(), unit, l.amount(), a == null ? null : a.endDate(), c.state,
                a == null ? null : a.tradeMethodName(), score, pos, List.copyOf(c.specPositions), tenderId, null, null);
    }

    /** Состояние по статусу лота и сроку объявления (спека §11.1 п. 3). */
    static String state(Integer lotStatus, String endDate, LocalDateTime now) {
        LocalDateTime end = ImportDeadline.parse(endDate);
        if (lotStatus != null && ProductWatchService.OPEN_LOT.contains(lotStatus) && (end == null || end.isAfter(now))) return "OPEN";
        if (Objects.equals(lotStatus, 370)) return "FAILED";
        if (Objects.equals(lotStatus, 360)) return "DONE";
        if (Objects.equals(lotStatus, 410) || Objects.equals(lotStatus, 430)) return "CANCELLED";
        return "CLOSED";
    }

    static Set<String> prefixSet(String text) {
        return ProductText.words(ProductText.normalize(text)).stream().filter(ProductText::significant).map(ProductText::prefix)
                .collect(Collectors.toSet());
    }

    /** Доля начал значимых слов позиции (название + бренд), найденных в тексте кандидата. */
    static double similarity(Position p, Set<String> candidate) {
        List<String> words = prefixSet(p.name() + " " + Objects.toString(p.brand(), "")).stream().toList();
        if (words.isEmpty()) return 0;
        return (double) words.stream().filter(candidate::contains).count() / words.size();
    }

    /** Позиции, у которых окно из 3 или 2 значимых слов названия встречается в ТЗ (как фразы §5.4). */
    static List<Integer> specPositions(List<Position> ps, String spec) {
        List<String> sp = ProductText.prefixes(spec);
        Map<String, List<Integer>> idx = CatalogPhrases.index(sp);
        List<Integer> out = new ArrayList<>();
        for (Position p : ps) {
            List<String> w = ProductText.words(ProductText.normalize(p.name())).stream()
                    .filter(ProductText::significant).map(ProductText::prefix).toList();
            boolean found = false;
            for (int n = Math.min(3, w.size()); n >= 2 && !found; n--) {
                for (int i = 0; i + n <= w.size() && !found; i++) found = CatalogPhrases.occurs(w.subList(i, i + n), sp, idx);
            }
            if (found) out.add(p.lineNo());
        }
        return out;
    }

    /** ТЗ лота: разобранное в АИС — из базы; иначе PDF с площадки (без записи). Не вышло — null. */
    private String spec(String anno, FoundLot head) {
        try {
            if (head.lotNumber() != null) {
                List<String> parsed = tenders.findParsedLotSpec(anno, head.lotNumber());
                if (!parsed.isEmpty() && parsed.get(0) != null && !parsed.get(0).isBlank()) return parsed.get(0);
            }
            LotTechSpecRef ref = call(() -> client.fetchLotTechSpec(anno, head.nameRu()));
            if (ref == null || ref.filePath() == null) return null;
            byte[] pdf = call(() -> client.downloadFile(ref.filePath()));
            return TechSpecExtractor.russianSection(PdfTextExtractor.extract(pdf));
        } catch (RuntimeException e) {
            if (ProductWatchService.stopping(e)) throw e;
            log.debug("Найти тендеры: ТЗ {} не получено: {}", anno, e.toString());
            return null;
        }
    }

    private <T> T call(Supplier<T> s) {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("поиск прерван");
        }
        return UpstreamRetry.call(sleeper, s);
    }
}
