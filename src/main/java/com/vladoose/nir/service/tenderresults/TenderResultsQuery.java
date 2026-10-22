package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.dto.response.LotResultSummary;
import com.vladoose.nir.dto.response.LotResultView;
import com.vladoose.nir.dto.response.TenderResultSummary;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Итоги для экрана (спека 2026-10-22-tender-results-market-prices §11, §13): сводки — одним заходом на список, полный итог лота. */
@Component
public class TenderResultsQuery {

    static final Set<ResultEventKind> CALLS = EnumSet.of(ResultEventKind.NO_BIDS, ResultEventKind.FAILED_OTHER, ResultEventKind.OI_FAILED);

    private final TenderLotResultRepository results;
    private final TenderLotBidRepository bids;
    private final TenderResultEventRepository events;
    private final TenderLotRepository lots;
    private final TenderRepository tenders;
    private final GoszakupSubjectRepository subjects;

    public TenderResultsQuery(TenderLotResultRepository results, TenderLotBidRepository bids, TenderResultEventRepository events,
                              TenderLotRepository lots, TenderRepository tenders, GoszakupSubjectRepository subjects) {
        this.results = results;
        this.bids = bids;
        this.events = events;
        this.lots = lots;
        this.tenders = tenders;
        this.subjects = subjects;
    }

    @Transactional(readOnly = true)
    public Map<Long, LotResultSummary> lotSummaries(Collection<Long> lotIds) {
        if (lotIds.isEmpty()) return Map.of();
        List<TenderLotResult> rs = results.findByTenderLotIdIn(lotIds);
        if (rs.isEmpty()) return Map.of();
        Map<Long, TenderLot> lotById = lots.findAllById(rs.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        Map<Long, Set<ResultEventKind>> kinds = new HashMap<>();
        for (TenderResultEvent e : events.findByTenderLotIdIn(lotIds)) {
            kinds.computeIfAbsent(e.getTenderLotId(), k -> EnumSet.noneOf(ResultEventKind.class)).add(e.getKind());
        }
        Map<String, String> names = names(rs.stream().flatMap(r -> r.getAttempts().stream()).map(ResultAttempt::supplierBin)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, LotResultSummary> out = new HashMap<>();
        for (TenderLotResult r : rs) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot == null) continue;
            String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
            String oi = r.getAttempts().stream().filter(a -> "ОИ".equals(a.method()) && a.supplierBin() != null)
                    .map(a -> names.get(a.supplierBin())).filter(Objects::nonNull).findFirst().orElse(null);
            out.put(lot.getId(), new LotResultSummary(r.getOutcome().name(), lotText(r, unit, kinds.getOrDefault(lot.getId(), Set.of()), oi),
                    r.getFailReason() == null ? null : r.getFailReason().name(), r.getBidsCount(), r.getWinnerName(), r.getWinnerPrice(),
                    r.getOurPlace(), r.getOurRejected(), r.getContractNumber() != null,
                    r.getProtocolStatus() == null ? null : r.getProtocolStatus().name(), r.getResultsAt()));
        }
        return out;
    }

    /** Строка итога у лота (спека §11). */
    static String lotText(TenderLotResult r, String unit, Set<ResultEventKind> kinds, String oiSupplier) {
        String base = switch (r.getOutcome()) {
            case PENDING -> "Ждём итогов";
            case CANCELLED -> "Отменён";
            case DONE -> done(r, unit);
            case FAILED -> "✗ Не состоялся" + failTail(r, kinds, oiSupplier);
        };
        String ours = ours(r);
        return ours == null ? base : base + " · " + ours;
    }

    private static String done(TenderLotResult r, String unit) {
        StringBuilder b = new StringBuilder("✓ Состоялся");
        if (r.getWinnerName() != null || r.getWinnerBin() != null) b.append(" · победитель ").append(ResultTexts.who(r.getWinnerName(), r.getWinnerBin()));
        if (r.getWinnerPrice() != null) b.append(" · ").append(ResultTexts.money(r.getWinnerPrice())).append(" за ").append(unit);
        if (r.getBidsCount() != null && r.getBidsCount() > 0) {
            b.append(" · ").append(ResultTexts.bids(r.getBidsCount()));
            if (r.getMinPrice() != null && r.getMaxPrice() != null && r.getMinPrice().compareTo(r.getMaxPrice()) != 0) {
                b.append(" (").append(ResultTexts.range(r.getMinPrice(), r.getMaxPrice())).append(')');
            }
        }
        if (r.getContractNumber() != null) b.append(" · договор подписан");
        return b.toString();
    }

    private static String failTail(TenderLotResult r, Set<ResultEventKind> kinds, String oiSupplier) {
        if (r.getFailReason() == FailReason.ONE_BID || kinds.contains(ResultEventKind.ONE_BID)) {
            return " · 1 заявка → из одного источника" + (oiSupplier == null ? "" : " у " + oiSupplier)
                    + (r.getMinPrice() == null ? "" : ", " + ResultTexts.money(r.getMinPrice()))
                    + (kinds.contains(ResultEventKind.OI_FAILED) ? " — сорвалось, объявят заново"
                    : kinds.contains(ResultEventKind.OI_DONE) ? " — состоялся" : "");
        }
        if (r.getFailReason() == FailReason.NO_BIDS) return " · 0 заявок — объявят заново";
        if (kinds.contains(ResultEventKind.OI_FAILED)) return " · из одного источника сорвалось — объявят заново";
        if (r.getNextTenderId() != null) return " · объявлен повтор";
        return " · объявят заново";
    }

    private static String ours(TenderLotResult r) {
        if (Boolean.TRUE.equals(r.getOurRejected())) return "наша заявка отклонена";
        if (r.getOurPlace() == null) return null;
        if (r.getOutcome() == LotOutcome.DONE && r.getOurPlace() == 1) return "выиграли";
        return "наше место " + r.getOurPlace() + (r.getBidsCount() == null ? "" : " из " + r.getBidsCount());
    }

    @Transactional(readOnly = true)
    public Map<Long, TenderResultSummary> tenderSummaries(Collection<Long> tenderIds) {
        if (tenderIds.isEmpty()) return Map.of();
        List<TenderLotResult> rs = results.findByTenderIdIn(tenderIds);
        if (rs.isEmpty()) return Map.of();
        Set<Long> calls = events.findByTenderIdInAndKindIn(tenderIds, CALLS).stream().map(TenderResultEvent::getTenderId)
                .collect(Collectors.toSet());
        Map<Long, List<TenderLotResult>> byTender = rs.stream().collect(Collectors.groupingBy(TenderLotResult::getTenderId));
        Map<Long, TenderResultSummary> out = new HashMap<>();
        byTender.forEach((id, list) -> {
            boolean call = calls.contains(id);
            boolean done = list.stream().anyMatch(r -> r.getOutcome() == LotOutcome.DONE);
            boolean ours = list.stream().anyMatch(r -> r.getOurPlace() != null || r.getOurRejected() != null);
            out.put(id, new TenderResultSummary(chip(list, call), call, done, ours));
        });
        return out;
    }

    /** Чип списка: «не состоялся · 0 заявок» / «итог: ИП X 251 тыс. ₸» / «объявлен повтор» / «отменён» / «ждём итогов». */
    static String chip(List<TenderLotResult> list, boolean call) {
        if (call) {
            boolean none = list.stream().anyMatch(r -> r.getFailReason() == FailReason.NO_BIDS);
            return none ? "не состоялся · 0 заявок" : "не состоялся — звонить";
        }
        Optional<TenderLotResult> won = list.stream().filter(r -> r.getOutcome() == LotOutcome.DONE && r.getWinnerName() != null).findFirst();
        if (won.isPresent()) {
            String name = ResultTexts.shortName(won.get().getWinnerName());
            if (name.length() > 30) name = name.substring(0, 29).trim() + "…";
            String price = ResultTexts.compact(won.get().getWinnerPrice());
            return "итог: " + name + (price.isEmpty() ? "" : " " + price);
        }
        if (list.stream().anyMatch(r -> r.getOutcome() == LotOutcome.DONE)) return "состоялся";
        if (list.stream().anyMatch(r -> r.getNextTenderId() != null)) return "объявлен повтор";
        if (list.stream().anyMatch(r -> r.getFailReason() == FailReason.ONE_BID)) return "1 заявка → из одного источника";
        if (list.stream().allMatch(r -> r.getOutcome() == LotOutcome.CANCELLED)) return "отменён";
        if (list.stream().anyMatch(r -> r.getOutcome() == LotOutcome.FAILED)) return "не состоялся";
        return "ждём итогов";
    }

    /** Полный итог лота (панель «Итог»). */
    @Transactional(readOnly = true)
    public LotResultView view(long lotId) {
        TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден"));
        TenderLotResult r = results.findByTenderLotId(lotId).orElse(null);
        if (r == null) {
            return new LotResultView(lotId, null, "Итоги ещё не проверялись", null, null, null, null, null, null, null, null, null, null,
                    List.of(), null, List.of(), null, null, null, null);
        }
        List<TenderLotBid> list = bids.findByResultIdOrderByPlaceAscIdAsc(r.getId());
        Set<String> bins = new HashSet<>();
        if (r.getContractSupplierBin() != null) bins.add(r.getContractSupplierBin());
        r.getAttempts().stream().map(ResultAttempt::supplierBin).filter(Objects::nonNull).forEach(bins::add);
        Map<String, String> names = names(bins);
        String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
        Set<ResultEventKind> kinds = events.findByTenderLotIdIn(List.of(lotId)).stream().map(TenderResultEvent::getKind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ResultEventKind.class)));
        String oi = r.getAttempts().stream().filter(a -> "ОИ".equals(a.method()) && a.supplierBin() != null)
                .map(a -> names.get(a.supplierBin())).filter(Objects::nonNull).findFirst().orElse(null);
        String anno = lot.getTender().getSourceExtId();
        List<LotResultView.Attempt> attempts = r.getAttempts().stream().map(a -> new LotResultView.Attempt(a.anno(), a.lotNumber(), a.method(),
                ResultTexts.methodName(a.method()), a.status(), a.statusId(), a.publishedAt(), a.resultsAt(), a.supplierBin(),
                a.supplierBin() == null ? null : names.get(a.supplierBin()), a.tenderId(),
                Objects.equals(a.anno(), anno) && Objects.equals(a.lotNumber(), lot.getSourceLotCode()))).toList();
        LotResultView.Contract contract = r.getContractNumber() == null ? null : new LotResultView.Contract(r.getContractNumber(),
                r.getContractSignedAt(), r.getContractUnitPrice(), r.getContractSum(), r.getContractSupplierBin(),
                r.getContractSupplierBin() == null ? null : names.get(r.getContractSupplierBin()));
        String nextNumber = r.getNextTenderId() == null ? null : tenders.findById(r.getNextTenderId()).map(Tender::getTenderNumber).orElse(null);
        return new LotResultView(lotId, r.getOutcome().name(), lotText(r, unit, kinds, oi), r.getLotStatus(),
                r.getFailReason() == null ? null : r.getFailReason().name(), r.getFailText(), r.getResultsAt(), r.getProtocolUrl(),
                r.getProtocolName(), r.getProtocolStatus() == null ? null : r.getProtocolStatus().name(), r.getProtocolNote(), r.getBidsCount(),
                r.getRejectedCount(), list.stream().map(b -> new LotResultView.Bid(b.getPlace(), b.getSupplierName(), b.getSupplierBin(),
                        b.getUnitPrice(), b.getTotal(), b.getSubmittedAt(), b.isRejected(), b.getRejectReason(), b.isWinner(), b.isSecondPlace(),
                        b.isOurs())).toList(),
                contract, attempts, r.getNextTenderId(), nextNumber, r.getCheckedAt(), r.getFinalAt());
    }

    private Map<String, String> names(Collection<String> bins) {
        if (bins.isEmpty()) return Map.of();
        return subjects.findByBinIn(bins).stream().filter(s -> s.getNameRu() != null && !s.getNameRu().isBlank())
                .collect(Collectors.toMap(GoszakupSubject::getBin, GoszakupSubject::getNameRu, (a, b) -> a));
    }
}
