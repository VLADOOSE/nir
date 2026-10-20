package com.vladoose.nir.productwatch;

import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.*;

/** Управляемый фейк v3-поиска: ответы по слову, объявления по номеру, отказ 403 с N-го вызова. */
public class FakeGoszakupSearchClient implements GoszakupSearchClient {
    public boolean configured = true;
    public final Map<String, List<FoundLot>> lotsByWord = new HashMap<>();
    public final Map<String, List<FoundAnnouncement>> buysByWord = new HashMap<>();
    public final Map<String, FoundAnnouncement> byNumber = new HashMap<>();
    public final Map<String, List<FoundLot>> lotsByAnno = new HashMap<>();
    public final Map<String, List<FoundPlanPoint>> plansByWord = new HashMap<>();
    public final Map<Long, FoundPlanPoint> plansById = new HashMap<>();
    public final Map<String, List<ContractInfo>> contracts = new HashMap<>();
    public final List<String> calls = new ArrayList<>();
    /** С какого по счёту вызова отвечать 403 (0 — никогда). */
    public int forbidFromCall = 0;

    private void call(String what) {
        calls.add(what);
        if (forbidFromCall > 0 && calls.size() >= forbidFromCall) {
            throw new GoszakupCallException("goszakup API 403 на /v3/graphql", 403, false, null);
        }
    }

    @Override public boolean isConfigured() { return configured; }
    @Override public SearchPage<FoundLot> searchLots(String w, List<Integer> s, LocalDate f, LocalDate t, Long a) {
        call("lots:" + w); return new SearchPage<>(lotsByWord.getOrDefault(w, List.of()), null);
    }
    @Override public SearchPage<FoundAnnouncement> searchAnnouncements(String w, List<Integer> s, LocalDate f, LocalDate t, Long a) {
        call("buys:" + w); return new SearchPage<>(buysByWord.getOrDefault(w, List.of()), null);
    }
    @Override public FoundAnnouncement announcement(String n) { call("anno:" + n); return byNumber.get(n); }
    @Override public List<FoundLot> lotsOfAnnouncement(String n) { call("annoLots:" + n); return lotsByAnno.getOrDefault(n, List.of()); }
    @Override public SearchPage<FoundPlanPoint> searchPlans(String w, List<Integer> y, List<Integer> s, Long a) {
        call("plans:" + w); return new SearchPage<>(plansByWord.getOrDefault(w, List.of()), null);
    }
    @Override public List<FoundPlanPoint> plansById(List<Long> ids) {
        call("plansById"); return ids.stream().map(plansById::get).filter(Objects::nonNull).toList();
    }
    @Override public List<ContractInfo> contracts(String n) { call("contracts:" + n); return contracts.getOrDefault(n, List.of()); }
}
