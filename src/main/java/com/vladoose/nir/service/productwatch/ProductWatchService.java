package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.*;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Поиск нашей продукции по стране (спека 2026-10-19-product-watch §7): слова включённых групп → лоты и объявления с
 * идущим приёмом за 60 дней → у себя правила (§5) → новое заводится тем же писателем, что импорт ЗКО, заведённое
 * поиском — обновляется, чужое (ЗКО, СК-Фармация) — только отметки. Сеть — вне транзакции (§6 CLAUDE.md); пауза
 * 300 мс между запросами; 401 / 403 / 429 — прогон останавливается.
 */
@Service
public class ProductWatchService {

    private static final Logger log = LoggerFactory.getLogger(ProductWatchService.class);

    public static final List<Integer> OPEN_LOT = List.of(190, 210, 220, 230, 240);
    public static final List<Integer> OPEN_BUY = List.of(210, 220, 230, 240, 245);
    static final int WINDOW_DAYS = 60;
    static final int MAX_PAGES = 5;
    static final long PAUSE_MS = 300;

    private final GoszakupSearchClient search;
    private final GoszakupClient client;
    private final GoszakupTenderWriter writer;
    private final ProductMatchService matches;
    private final CatalogSource catalog;
    private final TenderRepository tenders;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public ProductWatchService(GoszakupSearchClient search, GoszakupClient client, GoszakupTenderWriter writer,
                               ProductMatchService matches, ProductCatalog catalog, TenderRepository tenders) {
        this(search, client, writer, matches, catalog, tenders, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public ProductWatchService(GoszakupSearchClient search, GoszakupClient client, GoszakupTenderWriter writer,
                               ProductMatchService matches, CatalogSource catalog, TenderRepository tenders, Clock clock,
                               UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.client = client; this.writer = writer; this.matches = matches;
        this.catalog = catalog; this.tenders = tenders; this.clock = clock; this.sleeper = sleeper;
    }

    /** Стоп-коды: повтор не поможет, а долбёжка жжёт общий с продом токен. */
    public static boolean stopping(RuntimeException e) {
        return e instanceof GoszakupCallException g && (g.status() == 401 || g.status() == 403 || g.status() == 429);
    }

    /**
     * «Завести в АИС» из «Найти тендеры» (спека §11.2): объявление по номеру — без проверки «наш лот»; уже есть — его id.
     * Отметки «наш товар» считаются, как у найденного поиском.
     */
    public long importAnnouncement(String numberAnno) {
        Optional<Tender> existing = tenders.findBySourceExtId(numberAnno);
        if (existing.isPresent()) return existing.get().getId();
        FoundAnnouncement a = UpstreamRetry.call(sleeper, () -> search.announcement(numberAnno));
        if (a == null) throw new com.vladoose.nir.exception.NotFoundException("Объявление " + numberAnno + " на goszakup не найдено");
        List<LotDto> lots = UpstreamRetry.call(sleeper, () -> client.fetchLots(numberAnno));
        SubjectDto subj = a.orgBin() == null ? null : UpstreamRetry.call(sleeper, () -> client.fetchSubject(a.orgBin()));
        writer.upsertFound(a.toTrdBuy(), subj, lots, a.tradeMethodName());
        long id = tenders.findBySourceExtId(numberAnno).orElseThrow().getId();
        matches.evaluate(id, ProductMatchSource.SEARCH);
        return id;
    }

    public void run(ImportSummary sum) {
        if (!search.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        CatalogSnapshot snap = catalog.get();
        List<String> words = snap.groups().stream().flatMap(g -> g.searchWords().stream())
                .map(w -> w.strip().toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).distinct().toList();
        if (words.isEmpty()) {
            sum.setMessage("Нет включённых групп нашей продукции");
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        LocalDate today = now.toLocalDate();
        Map<String, FoundAnnouncement> candidates = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        try {
            for (String w : words) {
                collectLots(w, today, now, candidates, sum, notes);
                collectAnnouncements(w, today, now, candidates, sum, notes);
            }
        } catch (RuntimeException e) {
            if (!stopping(e)) throw e;
            sum.addError("поиск остановлен: " + ErrorText.of(e));
            finish(sum, notes);
            return;
        }
        Map<String, Optional<SubjectDto>> subjects = new HashMap<>();
        List<FoundAnnouncement> ordered = candidates.values().stream()
                .sorted(Comparator.comparing((FoundAnnouncement a) -> ImportDeadline.parse(a.endDate()),
                        Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        for (FoundAnnouncement a : ordered) {
            try {
                processCandidate(a, snap, sum, subjects);
            } catch (RuntimeException e) {
                sum.addError("объявление " + a.numberAnno() + ": " + ErrorText.of(e));
                log.warn("Поиск нашей продукции: объявление {}: {}", a.numberAnno(), ErrorText.of(e));
                if (stopping(e)) {
                    finish(sum, notes);
                    return;
                }
            }
        }
        refreshVanished(candidates.keySet(), sum);
        finish(sum, notes);
    }

    private void collectLots(String w, LocalDate today, LocalDateTime now, Map<String, FoundAnnouncement> out, ImportSummary sum,
                             List<String> notes) {
        Long after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            pause();
            Long a0 = after;
            SearchPage<FoundLot> p = UpstreamRetry.call(sleeper, () -> search.searchLots(w, OPEN_LOT,
                    today.minusDays(WINDOW_DAYS), today.plusDays(1), a0));
            sum.setFetched(sum.getFetched() + p.items().size());
            for (FoundLot l : p.items()) if (open(l.announcement(), now)) out.putIfAbsent(l.announcement().numberAnno(), l.announcement());
            after = p.nextAfter();
            if (after == null) return;
        }
        notes.add("«" + w + "»: больше " + MAX_PAGES + " страниц");
        log.warn("Поиск нашей продукции: по слову «{}» больше {} страниц — хвост не просмотрен", w, MAX_PAGES);
    }

    private void collectAnnouncements(String w, LocalDate today, LocalDateTime now, Map<String, FoundAnnouncement> out,
                                      ImportSummary sum, List<String> notes) {
        Long after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            pause();
            Long a0 = after;
            SearchPage<FoundAnnouncement> p = UpstreamRetry.call(sleeper, () -> search.searchAnnouncements(w, OPEN_BUY,
                    today.minusDays(WINDOW_DAYS), today.plusDays(1), a0));
            sum.setFetched(sum.getFetched() + p.items().size());
            for (FoundAnnouncement a : p.items()) if (open(a, now)) out.putIfAbsent(a.numberAnno(), a);
            after = p.nextAfter();
            if (after == null) return;
        }
        notes.add("объявления «" + w + "»: больше " + MAX_PAGES + " страниц");
    }

    /** Приём идёт: срок с временем площадки позже «сейчас», модуль госзакупа — текущий. */
    static boolean open(FoundAnnouncement a, LocalDateTime now) {
        if (a == null || a.numberAnno() == null) return false;
        if (a.systemId() != null && a.systemId() != 3) return false;
        LocalDateTime at = ImportDeadline.parse(a.endDate());
        return at != null && at.isAfter(now);
    }

    private void processCandidate(FoundAnnouncement a, CatalogSnapshot snap, ImportSummary sum, Map<String, Optional<SubjectDto>> subjects) {
        pause();
        List<LotDto> lots = UpstreamRetry.call(sleeper, () -> client.fetchLots(a.numberAnno()));
        List<LotInput> inputs = lots.stream().map(l -> new LotInput(0L, l.getNameRu(), l.getDescriptionRu(), null)).toList();
        if (lots.isEmpty() || ProductMatcher.match(snap, a.nameRu(), inputs).isEmpty()) {
            sum.setSkipped(sum.getSkipped() + 1);
            return;
        }
        sum.setMatched(sum.getMatched() + 1);
        Boolean found = writer.foundBySearch(a.numberAnno());
        if (found == null) {
            SubjectDto subj = subjectOf(a.orgBin(), subjects);
            if (writer.upsertFound(a.toTrdBuy(), subj, lots, a.tradeMethodName()) == GoszakupTenderWriter.Result.CREATED) {
                sum.addCreated(a.numberAnno());
            } else {
                sum.setUpdated(sum.getUpdated() + 1);
            }
        } else if (found) {
            writer.upsertFound(a.toTrdBuy(), null, lots, a.tradeMethodName());
            sum.setUpdated(sum.getUpdated() + 1);
        }
        Tender t = tenders.findBySourceExtId(a.numberAnno()).orElseThrow();
        matches.evaluate(t.getId(), ProductMatchSource.SEARCH);
        sum.getTouchedExtIds().add(a.numberAnno());
    }

    /** Заведённые поиском тендеры с идущим приёмом, которых нет в выдаче, — статус по номеру. */
    private void refreshVanished(Set<String> seen, ImportSummary sum) {
        for (Tender t : tenders.findByFoundByProductSearchTrueAndStatus("ACTIVE")) {
            if (seen.contains(t.getSourceExtId())) continue;
            try {
                pause();
                FoundAnnouncement a = UpstreamRetry.call(sleeper, () -> search.announcement(t.getSourceExtId()));
                if (a != null && writer.updateStatus(t.getSourceExtId(), a.refBuyStatusId(), a.endDate())) {
                    sum.setUpdated(sum.getUpdated() + 1);
                }
            } catch (RuntimeException e) {
                sum.addError("обновление " + t.getSourceExtId() + ": " + ErrorText.of(e));
                if (stopping(e)) return;
            }
        }
    }

    private SubjectDto subjectOf(String bin, Map<String, Optional<SubjectDto>> cache) {
        if (bin == null || bin.isBlank()) return null;
        return cache.computeIfAbsent(bin, b -> {
            try {
                pause();
                return Optional.ofNullable(UpstreamRetry.call(sleeper, () -> client.fetchSubject(b)));
            } catch (RuntimeException e) {
                if (stopping(e)) throw e;
                log.warn("Поиск нашей продукции: subject {} недоступен — тендер без заказчика: {}", b, ErrorText.of(e));
                return Optional.empty();
            }
        }).orElse(null);
    }

    private void finish(ImportSummary sum, List<String> notes) {
        sum.setMessage(String.format("Найдено %d, наших %d, новых %d, обновлено %d, ошибок %d%s", sum.getFetched(),
                sum.getMatched(), sum.getCreated(), sum.getUpdated(), sum.getErrors(),
                notes.isEmpty() ? "" : " · " + String.join("; ", notes)));
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("поиск прерван");
        }
    }
}
