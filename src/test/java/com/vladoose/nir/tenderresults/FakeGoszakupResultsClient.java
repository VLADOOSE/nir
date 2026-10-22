package com.vladoose.nir.tenderresults;

import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.*;

/** Управляемый фейк итогов goszakup: ответы по id объявления, пункту плана, слову, адресу файла; 403 с N-го вызова. */
public class FakeGoszakupResultsClient implements GoszakupResultsClient {
    public boolean configured = true;
    public final Map<Long, ResultBuy> buys = new HashMap<>();
    public final List<ContractRow> contracts = new ArrayList<>();
    public final Map<Long, List<AttemptLot>> attempts = new HashMap<>();
    public final Map<String, List<ClosedLot>> closed = new HashMap<>();
    public final Map<Long, ClosedLot> lotsById = new HashMap<>();
    public final Map<String, byte[]> files = new HashMap<>();
    public final Map<String, SubjectInfo> subjects = new HashMap<>();
    public final List<String> calls = new ArrayList<>();
    /** С какого по счёту вызова отвечать 403 (0 — никогда). */
    public int forbidFromCall = 0;
    /** Сбой скачивания протокола (не стоп-код). */
    public RuntimeException downloadError;
    /** Сбой справочника (не стоп-код). */
    public RuntimeException subjectError;

    private void call(String what) {
        calls.add(what);
        if (forbidFromCall > 0 && calls.size() >= forbidFromCall) {
            throw new GoszakupCallException("goszakup API 403 на /v3/graphql", 403, false, null);
        }
    }

    @Override public boolean isConfigured() { return configured; }

    @Override public List<ResultBuy> announcements(List<Long> ids) {
        call("buys:" + ids);
        return ids.stream().map(buys::get).filter(Objects::nonNull).toList();
    }

    @Override public List<ContractRow> contractsByBuyIds(List<Long> ids) {
        call("contracts:" + ids);
        return contracts.stream().filter(c -> ids.contains(c.buyId())).toList();
    }

    @Override public List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to) {
        call("customer:" + bin);
        return List.copyOf(contracts);
    }

    @Override public List<AttemptLot> lotsByPlanPoints(List<Long> points) {
        call("chain:" + points);
        return points.stream().flatMap(p -> attempts.getOrDefault(p, List.of()).stream()).toList();
    }

    @Override public SearchPage<ClosedLot> closedLots(String w, LocalDate f, LocalDate t, Long a) {
        call("closed:" + w + "@" + f);
        return new SearchPage<>(closed.getOrDefault(w, List.of()), null);
    }

    @Override public List<ClosedLot> lotsByIds(List<Long> ids) {
        call("lots:" + ids);
        return ids.stream().map(lotsById::get).filter(Objects::nonNull).toList();
    }

    @Override public byte[] downloadProtocol(String url) {
        call("file:" + url);
        if (downloadError != null) throw downloadError;
        return files.get(url);
    }

    @Override public SubjectInfo subject(String bin) {
        call("subject:" + bin);
        if (subjectError != null) throw subjectError;
        return subjects.get(bin);
    }
}
