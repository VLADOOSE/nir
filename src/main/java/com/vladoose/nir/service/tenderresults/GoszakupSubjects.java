package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.GoszakupSubject;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Справочник площадки по БИН (спека 2026-10-22-tender-results-market-prices §8): название, телефон, почта. У себя — 30 дней,
 * дальше — площадка; «не найден» тоже запоминается. Сбой площадки — что есть; 401 / 403 / 429 — наверх (общий токен).
 */
@Service
public class GoszakupSubjects {

    static final Duration FRESH = Duration.ofDays(30);
    static final long PAUSE_MS = 300;

    private final GoszakupResultsClient client;
    private final GoszakupSubjectRepository repo;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public GoszakupSubjects(GoszakupResultsClient client, GoszakupSubjectRepository repo) {
        this(client, repo, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public GoszakupSubjects(GoszakupResultsClient client, GoszakupSubjectRepository repo, Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.client = client;
        this.repo = repo;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public SubjectInfo get(String bin) {
        if (bin == null || !bin.matches("\\d{12}")) return null;
        OffsetDateTime now = OffsetDateTime.now(clock);
        GoszakupSubject s = repo.findById(bin).orElse(null);
        if (s != null && s.getFetchedAt().isAfter(now.minus(FRESH))) return info(s);
        if (!client.isConfigured()) return info(s);
        try {
            SubjectInfo fresh;
            try {
                fresh = UpstreamRetry.call(sleeper, () -> client.subject(bin));
            } finally {
                pause();   // токен общий с продом (спека §14)
            }
            GoszakupSubject row = s != null ? s : GoszakupSubject.builder().bin(bin).build();
            if (fresh != null) {
                row.setNameRu(cut(fresh.nameRu(), 500));
                row.setPhone(cut(fresh.phone(), 100));
                row.setEmail(cut(fresh.email(), 300));
            }
            row.setNotFound(fresh == null);
            row.setFetchedAt(now);
            return info(repo.save(row));
        } catch (RuntimeException e) {
            if (ProductWatchService.stopping(e)) throw e;
            return info(s);
        }
    }

    /** Названия: из справочника; без записи — с площадки, не больше fetchLimit за вызов (стоп-код — остальные без названий). */
    public Map<String, String> names(Collection<String> bins, int fetchLimit) {
        Set<String> want = bins.stream().filter(b -> b != null && b.matches("\\d{12}"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> out = new HashMap<>();
        Set<String> known = new HashSet<>();
        for (GoszakupSubject s : repo.findByBinIn(want)) {
            known.add(s.getBin());
            if (s.getNameRu() != null) out.put(s.getBin(), s.getNameRu());
        }
        int fetched = 0;
        for (String b : want) {
            if (known.contains(b) || fetched >= fetchLimit) continue;
            fetched++;
            try {
                SubjectInfo i = get(b);
                if (i != null && i.nameRu() != null) out.put(b, i.nameRu());
            } catch (RuntimeException e) {
                break;
            }
        }
        return out;
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static SubjectInfo info(GoszakupSubject s) {
        if (s == null || s.isNotFound() && s.getNameRu() == null) return null;
        return new SubjectInfo(s.getBin(), s.getNameRu(), s.getPhone(), s.getEmail());
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
