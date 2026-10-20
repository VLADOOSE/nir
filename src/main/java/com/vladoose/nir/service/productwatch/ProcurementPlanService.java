package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.integration.goszakup.dto.SearchPage;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.util.ErrorText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Планы закупок с нашими видами изделий (спека 2026-10-19-product-watch §10): по словам включённых групп — пункты
 * «Утверждён» на этот и следующий год; услуги отсекаются; правила групп — как у лота; регион — по месту поставки.
 * Пункт, который перестал встречаться, — статус по id: «объявлен / снят».
 */
@Service
public class ProcurementPlanService {

    static final List<Integer> APPROVED = List.of(2);
    static final int MAX_PAGES = 5;
    static final long PAUSE_MS = 300;

    private final GoszakupSearchClient search;
    private final ProcurementPlanWriter writer;
    private final CatalogSource catalog;
    private final RegionResolver regions;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public ProcurementPlanService(GoszakupSearchClient search, ProcurementPlanWriter writer, ProductCatalog catalog, RegionResolver regions) {
        this(search, writer, catalog, regions, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public ProcurementPlanService(GoszakupSearchClient search, ProcurementPlanWriter writer, CatalogSource catalog, RegionResolver regions,
                                  Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.writer = writer; this.catalog = catalog; this.regions = regions; this.clock = clock; this.sleeper = sleeper;
    }

    /** @return true — первый сбор (пунктов ещё не было): сводка в этот раз молчит. */
    public boolean collect(ImportSummary sum) {
        boolean first = writer.isEmpty();
        if (!search.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return first;
        }
        OffsetDateTime runStart = OffsetDateTime.now(clock);
        int year = LocalDate.now(clock.withZone(Market.KZ.zone())).getYear();
        CatalogSnapshot snap = catalog.get();
        Map<Long, ProcurementPlanWriter.Collected> found = new LinkedHashMap<>();
        try {
            for (String w : snap.groups().stream().flatMap(g -> g.searchWords().stream()).map(x -> x.strip().toLowerCase(Locale.ROOT)).distinct().toList()) {
                Long after = null;
                for (int page = 0; page < MAX_PAGES; page++) {
                    pause();
                    Long a0 = after;
                    SearchPage<FoundPlanPoint> p = UpstreamRetry.call(sleeper, () -> search.searchPlans(w, List.of(year, year + 1), APPROVED, a0));
                    sum.setFetched(sum.getFetched() + p.items().size());
                    for (FoundPlanPoint pp : p.items()) collected(pp, snap).ifPresent(c -> found.merge(pp.id(), c, ProcurementPlanService::union));
                    after = p.nextAfter();
                    if (after == null) break;
                }
            }
        } catch (RuntimeException e) {
            sum.addError("планы: " + ErrorText.of(e));
            if (ProductWatchService.stopping(e)) {
                sum.setMessage("Планы: сбор остановлен — " + ErrorText.of(e));
                return first;
            }
        }
        int[] cu = writer.upsert(new ArrayList<>(found.values()), runStart);
        sum.setMatched(found.size());
        sum.setCreated(cu[0]);
        sum.setUpdated(cu[1]);
        goneCheck(found.keySet(), sum);
        sum.setMessage(String.format("Планы: найдено %d, наших %d, новых %d, ошибок %d", sum.getFetched(), found.size(), cu[0], sum.getErrors()));
        return first;
    }

    Optional<ProcurementPlanWriter.Collected> collected(FoundPlanPoint p, CatalogSnapshot snap) {
        if (p.id() == null || p.nameRu() == null) return Optional.empty();
        String unit = Objects.toString(p.unit(), "").toLowerCase(Locale.ROOT);
        if (unit.contains("услуг") || MedicalGoodsVocabulary.hasServiceStrong(p.nameRu())) return Optional.empty();
        String text = ProductText.normalize(String.join(" ", p.nameRu(), Objects.toString(p.descRu(), ""), Objects.toString(p.extraDescRu(), "")));
        Map<Long, String> groups = new LinkedHashMap<>();
        ProductMatchLevel level = null;
        for (GroupRule r : snap.groups()) {
            if (r.brandGroup()) {
                BrandModels.Hit h = BrandModels.first(r.codes(), text);
                if (h != null) { groups.put(r.id(), h.label()); level = ProductMatchLevel.NAMED; }
                continue;
            }
            String word = r.typeWord(text, "", false);
            if (word == null) continue;
            groups.put(r.id(), word);
            boolean named = BrandModels.first(snap.groupCodes(r.id()), text) != null || BrandModels.first(snap.globalCodes(), text) != null;
            level = ProductMatchLevel.max(level, named ? ProductMatchLevel.NAMED : ProductMatchLevel.TYPE);
        }
        if (groups.isEmpty()) return Optional.empty();
        String region = regions.resolve(p.deliveryPlace(), p.subjectNameRu());
        return Optional.of(new ProcurementPlanWriter.Collected(p, region, level, groups));
    }

    private static ProcurementPlanWriter.Collected union(ProcurementPlanWriter.Collected a, ProcurementPlanWriter.Collected b) {
        Map<Long, String> g = new LinkedHashMap<>(a.groupWords());
        b.groupWords().forEach(g::putIfAbsent);
        return new ProcurementPlanWriter.Collected(a.point(), a.region(), ProductMatchLevel.max(a.level(), b.level()), g);
    }

    private void goneCheck(Set<Long> seen, ImportSummary sum) {
        List<Long> stale = writer.openNotSeen(seen);
        Map<Long, String> gone = new HashMap<>();
        for (int i = 0; i < stale.size(); i += 200) {
            List<Long> batch = stale.subList(i, Math.min(stale.size(), i + 200));
            try {
                pause();
                Map<Long, FoundPlanPoint> byId = new HashMap<>();
                for (FoundPlanPoint p : UpstreamRetry.call(sleeper, () -> search.plansById(batch))) byId.put(p.id(), p);
                for (Long id : batch) {
                    FoundPlanPoint p = byId.get(id);
                    if (p == null) gone.put(id, "снят");
                    else if (p.statusId() == null || !APPROVED.contains(p.statusId())) gone.put(id, Objects.toString(p.statusName(), "изменён"));
                }
            } catch (RuntimeException e) {
                sum.addError("планы, статусы: " + ErrorText.of(e));
                if (ProductWatchService.stopping(e)) break;
            }
        }
        if (!gone.isEmpty()) writer.markGone(gone);
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("сбор планов прерван");
        }
    }
}
