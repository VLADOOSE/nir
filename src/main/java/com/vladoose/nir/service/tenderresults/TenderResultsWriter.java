package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.tenderresults.protocol.LotProtocol;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolBid;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolFormat;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Запись итогов (спека 2026-10-22-tender-results-market-prices §4–§7): каждая операция — своя транзакция, сеть и разбор файла —
 * у вызывающего (§6 CLAUDE.md). Лот площадки ↔ лот АИС — по номеру лота (`source_lot_code`).
 */
@Component
public class TenderResultsWriter {

    /** Сколько дней от срока (не состоялся — от итогов) лот проверяется. */
    static final int TRACK_DAYS = 30;
    /** Окно кандидатов прохода: 30 дней с запасом на итоги, опубликованные позже срока. */
    static final int WINDOW_DAYS = 45;
    static final int PROTOCOL_ATTEMPTS = 5;

    public record Candidate(long tenderId, String anno, long buyId) {}

    public record ProtocolJob(long tenderId, String url, List<Long> resultIds) {}

    private record Pick(ContractRow row, ContractUnitRow unit) {}

    private final TenderRepository tenders;
    private final TenderLotRepository lots;
    private final TenderLotResultRepository results;
    private final TenderLotBidRepository bids;
    private final TenderResultEventRepository eventRepo;
    private final GoszakupSubjectRepository subjects;
    private final ResultEvents events;

    public TenderResultsWriter(TenderRepository tenders, TenderLotRepository lots, TenderLotResultRepository results,
                               TenderLotBidRepository bids, TenderResultEventRepository eventRepo, GoszakupSubjectRepository subjects,
                               ResultEvents events) {
        this.tenders = tenders;
        this.lots = lots;
        this.results = results;
        this.bids = bids;
        this.eventRepo = eventRepo;
        this.subjects = subjects;
        this.events = events;
    }

    /** Итогов в базе нет — первый проход молчит (спека §7). */
    @Transactional(readOnly = true)
    public boolean firstRun() {
        return results.count() == 0;
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(LocalDateTime now, int limit) {
        List<Long> ids = tenders.findResultCandidates(now, now.minusDays(WINDOW_DAYS), limit);
        List<Candidate> out = new ArrayList<>();
        for (Tender t : tenders.findAllById(ids)) {
            Long buy = buyId(t.getSourceExtId());
            if (buy != null) out.add(new Candidate(t.getId(), t.getSourceExtId(), buy));
        }
        return out;
    }

    /** «Проверить итоги» одного тендера: goszakup KZ после срока, иначе 400. */
    @Transactional(readOnly = true)
    public Candidate candidate(long tenderId, LocalDateTime now) {
        Tender t = tenders.findById(tenderId).orElseThrow(() -> new NotFoundException("Тендер не найден"));
        Long buy = buyId(t.getSourceExtId());
        if (t.getMarket() != Market.KZ || t.getPlatform() != TenderPlatform.GOSZAKUP || t.getSource() != Source.PUBLIC_TENDER || buy == null) {
            throw new BadRequestException("Итоги — только для тендеров goszakup");
        }
        if (!ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now)) {
            throw new BadRequestException("Приём заявок ещё идёт — итогов пока нет");
        }
        return new Candidate(t.getId(), t.getSourceExtId(), buy);
    }

    /** «17758520-1» → 17758520. */
    public static Long buyId(String anno) {
        if (anno == null) return null;
        String head = anno.contains("-") ? anno.substring(0, anno.indexOf('-')) : anno;
        return head.trim().matches("\\d{1,12}") ? Long.parseLong(head.trim()) : null;
    }

    /** Статусы лотов объявления → итоги; лотам, которым нужен протокол, — задание на файл. */
    @Transactional
    public List<ProtocolJob> applyBuy(Candidate c, ResultBuy buy, OffsetDateTime now) {
        Tender t = tenders.findById(c.tenderId()).orElse(null);
        if (t == null || buy == null) return List.of();
        Map<String, ResultLot> byCode = new HashMap<>();
        for (ResultLot l : buy.lots()) if (l.lotNumber() != null) byCode.put(l.lotNumber().trim(), l);
        ProtocolFileRef file = buy.protocols().isEmpty() ? null : buy.protocols().get(buy.protocols().size() - 1);
        List<Long> need = new ArrayList<>();
        for (TenderLot lot : t.getLots()) {
            if (lot.getSourceRemovedAt() != null || lot.getSourceLotCode() == null) continue;
            ResultLot rl = byCode.get(lot.getSourceLotCode().trim());
            if (rl == null) continue;
            fillPlatform(lot, rl);
            if (t.getCustomerBin() == null && rl.customerBin() != null && rl.customerBin().matches("\\d{12}")) {
                t.setCustomerBin(rl.customerBin());
            }
            TenderLotResult r = results.findByTenderLotId(lot.getId())
                    .orElseGet(() -> TenderLotResult.builder().tenderLotId(lot.getId()).tenderId(t.getId()).createdAt(now).build());
            if (r.getFinalAt() != null) continue;
            LotOutcome o = LotOutcome.ofLotStatus(rl.statusId());
            r.setLotStatusId(rl.statusId());
            r.setLotStatus(cut(rl.statusName(), 100));
            r.setOutcome(o);
            if (o != LotOutcome.PENDING && r.getResultsAt() == null) r.setResultsAt(ImportDeadline.parse(buy.resultsAt()));
            if (file != null && file.url() != null) {
                r.setProtocolUrl(cut(file.url(), 300));
                r.setProtocolName(cut(file.name(), 200));
            }
            r.setCheckedAt(now);
            boolean wants = (o == LotOutcome.DONE || o == LotOutcome.FAILED) && r.getProtocolStatus() != ProtocolStatus.PARSED
                    && r.getProtocolStatus() != ProtocolStatus.UNKNOWN_FORMAT && r.getProtocolAttempts() < PROTOCOL_ATTEMPTS;
            if (wants && r.getProtocolUrl() == null) {
                r.setProtocolStatus(ProtocolStatus.NO_FILE);
                r.setProtocolAttempts(r.getProtocolAttempts() + 1);
                r.setProtocolNote("файла протокола на площадке ещё нет");
                wants = false;
            }
            r = results.save(r);
            if (wants) need.add(r.getId());
        }
        String url = need.isEmpty() ? null : results.findById(need.get(0)).map(TenderLotResult::getProtocolUrl).orElse(null);
        return need.isEmpty() || url == null ? List.of() : List.of(new ProtocolJob(t.getId(), url, need));
    }

    static void fillPlatform(TenderLot lot, ResultLot rl) {
        if (lot.getExtLotId() == null) lot.setExtLotId(rl.id());
        if (lot.getPlanPointId() == null) lot.setPlanPointId(rl.planPointId());
        if (lot.getEnstruCode() == null && rl.enstruCode() != null) lot.setEnstruCode(cut(rl.enstruCode(), 30));
        if (lot.getPlanUnit() == null && rl.unit() != null) lot.setPlanUnit(cut(rl.unit(), 50));
    }

    /** Файл не скачан или не разобран — попытка, пометка; после 5 попыток итог заводится без протокола. */
    @Transactional
    public void protocolFailed(ProtocolJob job, String note) {
        for (TenderLotResult r : results.findAllById(job.resultIds())) {
            r.setProtocolAttempts(r.getProtocolAttempts() + 1);
            r.setProtocolStatus(ProtocolStatus.FAILED);
            r.setProtocolNote(cut(note, 300));
        }
    }

    @Transactional
    public void applyProtocol(ProtocolJob job, ProtocolResult parsed, String ourBin) {
        for (TenderLotResult r : results.findAllById(job.resultIds())) {
            TenderLot lot = lots.findById(r.getTenderLotId()).orElse(null);
            if (lot == null) continue;
            LotProtocol lp = parsed.format() == ProtocolFormat.UNKNOWN ? null : parsed.lots().get(lot.getSourceLotCode().trim());
            if (lp == null) {
                r.setProtocolStatus(ProtocolStatus.UNKNOWN_FORMAT);
                r.setProtocolNote(parsed.format() == ProtocolFormat.UNKNOWN ? "вид протокола не знаком — откройте файл"
                        : "лота нет в протоколе — откройте файл");
                continue;
            }
            write(r, lot, lp, ourBin);
        }
    }

    private record ProtocolFile(long tenderId, String url) {}

    /** Итоги, помеченные к переразбору протокола (V45), — задание на файл по каждому протоколу тендера. */
    @Transactional(readOnly = true)
    public List<ProtocolJob> reparseJobs(Long tenderId) {
        List<TenderLotResult> marked = tenderId == null ? results.findByProtocolReparseTrueOrderById()
                : results.findByTenderIdAndProtocolReparseTrueOrderById(tenderId);
        Map<ProtocolFile, List<Long>> byFile = new LinkedHashMap<>();
        for (TenderLotResult r : marked) {
            if (r.getProtocolUrl() == null) continue;
            byFile.computeIfAbsent(new ProtocolFile(r.getTenderId(), r.getProtocolUrl()), k -> new ArrayList<>()).add(r.getId());
        }
        List<ProtocolJob> out = new ArrayList<>();
        byFile.forEach((f, ids) -> out.add(new ProtocolJob(f.tenderId(), f.url(), ids)));
        return out;
    }

    /**
     * Переразбор (V45): лот нашёлся в протоколе — итог из него; не нашёлся или файл не получен (parsed пусто) — прежний итог
     * остаётся. Пометка снимается в любом случае — файл не качается каждый час; события второй раз не заводятся.
     */
    @Transactional
    public void applyReparse(ProtocolJob job, ProtocolResult parsed, String ourBin) {
        for (TenderLotResult r : results.findAllById(job.resultIds())) {
            r.setProtocolReparse(false);
            if (parsed == null || parsed.format() == ProtocolFormat.UNKNOWN) continue;
            TenderLot lot = lots.findById(r.getTenderLotId()).orElse(null);
            LotProtocol lp = lot == null || lot.getSourceLotCode() == null ? null : parsed.lots().get(lot.getSourceLotCode().trim());
            if (lp != null) write(r, lot, lp, ourBin);
        }
    }

    private void write(TenderLotResult r, TenderLot lot, LotProtocol lp, String ourBin) {
        bids.deleteByResultId(r.getId());
        r.setWinnerName(null);
        r.setWinnerBin(null);
        r.setWinnerPrice(null);
        r.setSecondName(null);
        r.setSecondBin(null);
        r.setSecondPrice(null);
        r.setOurPlace(null);
        r.setOurPrice(null);
        r.setOurRejected(null);
        BigDecimal min = null, max = null;
        for (ProtocolBid b : lp.bids()) {
            BigDecimal unit = unitPrice(b, lot);
            boolean winner = !b.rejected() && b.bin() != null && b.bin().equals(lp.winnerBin());
            boolean second = !b.rejected() && b.bin() != null && b.bin().equals(lp.secondBin());
            boolean ours = ourBin != null && ourBin.equals(b.bin());
            bids.save(TenderLotBid.builder().resultId(r.getId()).place(b.place()).supplierName(cut(b.name(), 300))
                    .supplierBin(cut(b.bin(), 20)).unitPrice(unit).total(b.total()).submittedAt(b.submittedAt()).rejected(b.rejected())
                    .rejectReason(cut(b.rejectReason(), 500)).winner(winner).secondPlace(second).ours(ours).build());
            if (!b.rejected() && unit != null) {
                if (min == null || unit.compareTo(min) < 0) min = unit;
                if (max == null || unit.compareTo(max) > 0) max = unit;
            }
            if (winner && r.getWinnerBin() == null) {
                r.setWinnerName(cut(b.name(), 300));
                r.setWinnerBin(b.bin());
                r.setWinnerPrice(unit);
            }
            if (second && r.getSecondBin() == null) {
                r.setSecondName(cut(b.name(), 300));
                r.setSecondBin(b.bin());
                r.setSecondPrice(unit);
            }
            if (ours) {
                r.setOurPlace(b.rejected() ? null : b.place());
                r.setOurPrice(unit);
                r.setOurRejected(b.rejected());
            }
        }
        if (r.getWinnerBin() == null && lp.winnerBin() != null) r.setWinnerBin(lp.winnerBin());
        r.setBidsCount(lp.bidsCount());
        r.setRejectedCount(lp.rejectedCount());
        r.setMinPrice(min);
        r.setMaxPrice(max);
        r.setFailReason(lp.failReason());
        r.setFailText(cut(lp.conclusion(), 300));
        r.setProtocolStatus(ProtocolStatus.PARSED);
        r.setProtocolNote(null);
    }

    /** Цена за единицу: из протокола; в протоколе только сумма — сумма / количество лота; нет количества — пусто (Review Focus 4). */
    static BigDecimal unitPrice(ProtocolBid b, TenderLot lot) {
        if (b.unitPrice() != null) return b.unitPrice();
        if (b.total() == null || lot.getQuantity() == null || lot.getQuantity() <= 0) return null;
        return b.total().divide(BigDecimal.valueOf(lot.getQuantity()), 2, RoundingMode.HALF_UP);
    }

    /** Номера объявлений, у которых состоявшиеся лоты ждут договора. */
    @Transactional(readOnly = true)
    public List<Long> buysAwaitingContract(Long tenderId) {
        Set<Long> ids = results.findDoneWithoutContract().stream().map(TenderLotResult::getTenderId)
                .filter(id -> tenderId == null || id.equals(tenderId)).collect(Collectors.toCollection(LinkedHashSet::new));
        return tenders.findAllById(ids).stream().map(t -> buyId(t.getSourceExtId())).filter(Objects::nonNull).distinct().toList();
    }

    /** Договоры: по лоту — последний подписанный, «не заключён / отказ / недействителен» мимо; итог окончательный. */
    @Transactional
    public int applyContracts(List<ContractRow> rows, LocalDateTime nowLocal, OffsetDateTime now, boolean silent) {
        Map<Long, Pick> best = new HashMap<>();
        for (ContractRow c : rows) {
            if (!c.concluded()) continue;
            for (ContractUnitRow u : c.units()) {
                if (u.lotId() == null) continue;
                Pick cur = best.get(u.lotId());
                if (cur == null || signed(c).isAfter(signed(cur.row()))) best.put(u.lotId(), new Pick(c, u));
            }
        }
        if (best.isEmpty()) return 0;
        List<TenderLotResult> open = results.findDoneWithoutContract();
        Map<Long, TenderLot> lotById = lots.findAllById(open.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        int n = 0;
        for (TenderLotResult r : open) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot == null || lot.getExtLotId() == null) continue;
            Pick p = best.get(lot.getExtLotId());
            if (p == null) continue;
            LocalDate signedAt = signed(p.row());
            r.setContractNumber(cut(p.row().number(), 60));
            r.setContractSignedAt(signedAt.equals(LocalDate.MIN) ? null : signedAt);
            r.setContractUnitPrice(p.unit().unitPrice());
            r.setContractSum(p.unit().totalWithVat() != null ? p.unit().totalWithVat() : p.row().sumWithVat());
            r.setContractSupplierBin(cut(p.row().supplierBin(), 20));
            r.setFinalAt(now);
            String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
            String text = ResultTexts.contract(name(p.row().supplierBin()), p.unit().unitPrice(), unit, p.row().number(), r.getContractSignedAt());
            boolean ours = events.ours(List.of(lot)).contains(lot.getId());
            if (events.add(lot.getTender(), lot, ResultEventKind.CONTRACT, null, ImportDeadline.parse(p.row().signDate()), text, null, ours,
                    silent, nowLocal, now)) n++;
        }
        return n;
    }

    private static LocalDate signed(ContractRow c) {
        LocalDateTime t = ImportDeadline.parse(c.signDate());
        return t == null ? LocalDate.MIN : t.toLocalDate();
    }

    /** Название из справочника площадки (проход заполняет его заранее), иначе «БИН …». */
    public String name(String bin) {
        if (bin == null) return null;
        return subjects.findById(bin).map(GoszakupSubject::getNameRu).filter(s -> !s.isBlank()).map(ResultTexts::shortName)
                .orElse("БИН " + bin);
    }

    /** События итогов: итог известен, протокол разобран (или не будет), события ещё нет (спека §7). */
    @Transactional
    public int settle(Long tenderId, LocalDateTime nowLocal, OffsetDateTime now, boolean silent) {
        List<TenderLotResult> todo = (tenderId == null ? results.findUnsettled() : results.findByTenderId(tenderId)).stream()
                .filter(r -> r.getOutcome() != LotOutcome.PENDING && r.getEventAt() == null && settled(r)).toList();
        if (todo.isEmpty()) return 0;
        Map<Long, TenderLot> lotById = lots.findAllById(todo.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        Set<Long> ours = events.ours(lotById.values());
        int n = 0;
        for (TenderLotResult r : todo) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot == null) continue;
            Tender t = lot.getTender();
            String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
            ResultEventKind kind = kind(r, lot);
            List<TenderLotBid> list = bids.findByResultIdOrderByPlaceAscIdAsc(r.getId());
            if (events.add(t, lot, kind, null, r.getResultsAt(), text(kind, r, lot, unit, list), null, ours.contains(lot.getId()),
                    silent, nowLocal, now)) n++;
            if (r.getOurRejected() != null) {
                TenderLotBid our = list.stream().filter(TenderLotBid::isOurs).findFirst().orElse(null);
                boolean won = our != null && our.isWinner();
                String text = ResultTexts.ourBid(won, r.getOurPlace(), r.getBidsCount(), r.getOurPrice(), r.getWinnerName(), r.getWinnerPrice(),
                        Boolean.TRUE.equals(r.getOurRejected()), our == null ? null : our.getRejectReason(), unit);
                if (events.add(t, lot, ResultEventKind.OUR_BID, null, r.getResultsAt(), text, null, true, silent, nowLocal, now)) n++;
            }
            r.setEventAt(now);
            if (r.getOutcome() == LotOutcome.CANCELLED) r.setFinalAt(now);
        }
        return n;
    }

    static boolean settled(TenderLotResult r) {
        return r.getOutcome() == LotOutcome.CANCELLED || r.getFinalAt() != null
                || r.getProtocolStatus() == ProtocolStatus.PARSED || r.getProtocolStatus() == ProtocolStatus.UNKNOWN_FORMAT
                || r.getProtocolAttempts() >= PROTOCOL_ATTEMPTS;
    }

    /** Вид события итога: лот из одного источника — свои виды; не состоялся — по причине протокола. */
    ResultEventKind kind(TenderLotResult r, TenderLot lot) {
        boolean oi = "ОИ".equals(ResultTexts.method(lot.getSourceLotCode()));
        return switch (r.getOutcome()) {
            case CANCELLED -> ResultEventKind.CANCELLED;
            case DONE -> oi ? ResultEventKind.OI_DONE : ResultEventKind.DONE;
            case FAILED -> oi ? ResultEventKind.OI_FAILED
                    : r.getFailReason() == FailReason.ONE_BID || r.getFailReason() == null && oiAfterOwn(r, lot) ? ResultEventKind.ONE_BID
                    : r.getFailReason() == FailReason.NO_BIDS ? ResultEventKind.NO_BIDS
                    : ResultEventKind.FAILED_OTHER;
            case PENDING -> throw new IllegalStateException("итог лота ещё не известен");
        };
    }

    private String text(ResultEventKind kind, TenderLotResult r, TenderLot lot, String unit, List<TenderLotBid> list) {
        TenderLotBid first = list.stream().filter(b -> !b.isRejected()).findFirst().orElse(list.isEmpty() ? null : list.get(0));
        return switch (kind) {
            case DONE -> ResultTexts.done(r, unit, lot.getMaxCost());
            case NO_BIDS -> ResultTexts.noBids();
            case ONE_BID -> first != null
                    ? ResultTexts.oneBid(ResultTexts.who(first.getSupplierName(), first.getSupplierBin()), first.getUnitPrice(), unit)
                    : ResultTexts.oneBid(oiSupplierAfterOwn(r, lot), null, unit);
            case FAILED_OTHER -> ResultTexts.failedOther(r.getFailText(), r.getProtocolStatus());
            case CANCELLED -> ResultTexts.cancelled();
            case OI_DONE -> ResultTexts.oiDone(r.getWinnerName() != null ? r.getWinnerName() : r.getWinnerBin() == null ? null : name(r.getWinnerBin()));
            case OI_FAILED -> ResultTexts.oiFailed(first == null ? null : ResultTexts.who(first.getSupplierName(), first.getSupplierBin()));
            default -> kind.name();
        };
    }

    public record ChainTask(long resultId, long lotId, long planPointId) {}

    public record ChainOutcome(int events, List<String> toImport) {}

    /** Не состоявшиеся лоты, цепочка которых не досмотрена (спека §6). */
    @Transactional(readOnly = true)
    public List<ChainTask> openChains(Long tenderId) {
        List<TenderLotResult> open = results.findOpenFailed().stream()
                .filter(r -> tenderId == null || r.getTenderId().equals(tenderId)).toList();
        Map<Long, TenderLot> lotById = lots.findAllById(open.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        List<ChainTask> out = new ArrayList<>();
        for (TenderLotResult r : open) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot != null && lot.getPlanPointId() != null) out.add(new ChainTask(r.getId(), lot.getId(), lot.getPlanPointId()));
        }
        return out;
    }

    /**
     * Попытки пункта плана (спека §6): хранятся у итога; события — по попыткам ПОСЛЕ своей и только тем, которых нет в АИС
     * (решение 3): «повтор объявлен», «из одного источника — состоялся / сорвалось». Поздняя попытка уже в АИС — дальше итоги
     * ведёт она (ссылка, итог окончательный). Повтор запросом цен или конкурсом с идущим приёмом — кандидат «Завести в АИС».
     */
    @Transactional
    public ChainOutcome applyChain(ChainTask task, List<AttemptLot> attempts, LocalDateTime nowLocal, OffsetDateTime now, boolean silent) {
        TenderLotResult r = results.findById(task.resultId()).orElse(null);
        TenderLot lot = lots.findById(task.lotId()).orElse(null);
        if (r == null || lot == null || r.getFinalAt() != null || attempts.isEmpty()) return new ChainOutcome(0, List.of());
        Tender t = lot.getTender();
        List<AttemptLot> sorted = attempts.stream().filter(a -> a.anno() != null)
                .sorted(Comparator.comparing((AttemptLot a) -> ImportDeadline.parse(a.publishDate()), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AttemptLot::lotId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<String, Long> inAis = tenders.findBySourceExtIdIn(sorted.stream().map(AttemptLot::anno).distinct().toList()).stream()
                .filter(x -> x.getMarket() == Market.KZ)
                .collect(Collectors.toMap(Tender::getSourceExtId, Tender::getId, (a, b) -> a));
        List<ResultAttempt> list = new ArrayList<>();
        for (AttemptLot a : sorted) {
            list.add(new ResultAttempt(a.anno(), a.lotNumber(), ResultTexts.method(a.lotNumber()), a.statusId(), cut(a.statusName(), 100),
                    a.publishDate(), a.resultsAt(), a.supplierBin(), inAis.get(a.anno())));
        }
        r.setAttempts(list);
        int own = -1;
        for (int i = 0; i < sorted.size(); i++) {
            AttemptLot a = sorted.get(i);
            if (Objects.equals(a.lotId(), lot.getExtLotId()) || a.anno().equals(t.getSourceExtId())) {
                own = i;
                break;
            }
        }
        if (own < 0) return new ChainOutcome(0, List.of());
        for (int i = list.size() - 1; i > own; i--) {
            Long id = list.get(i).tenderId();
            if (id != null && !id.equals(t.getId())) {
                r.setNextTenderId(id);
                r.setFinalAt(now);
                return new ChainOutcome(0, List.of());
            }
        }
        boolean ours = events.ours(List.of(lot)).contains(lot.getId());
        int n = 0;
        List<String> toImport = new ArrayList<>();
        for (int i = own + 1; i < sorted.size(); i++) {
            AttemptLot a = sorted.get(i);
            String m = ResultTexts.method(a.lotNumber());
            if ("ОИ".equals(m)) {
                LotOutcome o = LotOutcome.ofLotStatus(a.statusId());
                String supplier = a.supplierBin() == null ? null : name(a.supplierBin());
                LocalDateTime at = ImportDeadline.parse(a.resultsAt());
                if (o == LotOutcome.DONE && events.add(t, lot, ResultEventKind.OI_DONE, a.anno(), at, ResultTexts.oiDone(supplier), null,
                        ours, silent, nowLocal, now)) n++;
                if (o == LotOutcome.FAILED && events.add(t, lot, ResultEventKind.OI_FAILED, a.anno(), at, ResultTexts.oiFailed(supplier), null,
                        ours, silent, nowLocal, now)) n++;
            } else {
                LocalDateTime end = ImportDeadline.parse(a.endDate());
                if (events.add(t, lot, ResultEventKind.REPEAT, a.anno(), ImportDeadline.parse(a.publishDate()),
                        ResultTexts.repeat(a.anno(), ResultTexts.methodName(m), end), null, ours, silent, nowLocal, now)) n++;
                if (("ЗЦП".equals(m) || "ОК".equals(m)) && end != null && end.isAfter(nowLocal)) toImport.add(a.anno());
            }
        }
        LotOutcome last = LotOutcome.ofLotStatus(sorted.get(sorted.size() - 1).statusId());
        if (sorted.size() - 1 > own && (last == LotOutcome.DONE || last == LotOutcome.CANCELLED)) r.setFinalAt(now);
        return new ChainOutcome(n, toImport);
    }

    /** Повтор заведён в АИС: ссылка, номер тендера у попытки и у события «повтор» («История» ведёт на него), дальше итоги ведёт он. */
    @Transactional
    public void linkImported(long resultId, String anno, long tenderId, OffsetDateTime now) {
        results.findById(resultId).ifPresent(r -> {
            eventRepo.findFirstByTenderLotIdAndKindAndRefKey(r.getTenderLotId(), ResultEventKind.REPEAT, anno)
                    .ifPresent(e -> e.setRefTenderId(tenderId));
            List<ResultAttempt> list = new ArrayList<>();
            for (ResultAttempt a : r.getAttempts()) {
                list.add(anno.equals(a.anno()) ? new ResultAttempt(a.anno(), a.lotNumber(), a.method(), a.statusId(), a.status(),
                        a.publishedAt(), a.resultsAt(), a.supplierBin(), tenderId) : a);
            }
            r.setAttempts(list);
            r.setNextTenderId(tenderId);
            r.setFinalAt(now);
        });
    }

    /** Своя попытка не из одного источника, а следом в цепочке — закупка из одного источника. */
    static boolean oiAfterOwn(TenderLotResult r, TenderLot lot) {
        List<ResultAttempt> list = r.getAttempts();
        for (int i = 0; i < list.size(); i++) {
            if (!Objects.equals(list.get(i).lotNumber(), lot.getSourceLotCode())) continue;
            for (int j = i + 1; j < list.size(); j++) if ("ОИ".equals(list.get(j).method())) return true;
            return false;
        }
        return false;
    }

    /** Поставщик закупки из одного источника после своей попытки (для текста «1 заявка → …»). */
    private String oiSupplierAfterOwn(TenderLotResult r, TenderLot lot) {
        List<ResultAttempt> list = r.getAttempts();
        boolean after = false;
        for (ResultAttempt a : list) {
            if (Objects.equals(a.lotNumber(), lot.getSourceLotCode())) { after = true; continue; }
            if (after && "ОИ".equals(a.method()) && a.supplierBin() != null) return name(a.supplierBin());
        }
        return null;
    }

    /** Дальше не проверяем: прошло 30 дней от итогов (нет итогов — от срока приёма). */
    @Transactional
    public int closeStale(LocalDateTime nowLocal, OffsetDateTime now) {
        int n = 0;
        Map<Long, Tender> cache = new HashMap<>();
        for (TenderLotResult r : results.findOpen()) {
            LocalDateTime from = r.getResultsAt();
            if (from == null) {
                Tender t = cache.computeIfAbsent(r.getTenderId(), id -> tenders.findById(id).orElse(null));
                from = t == null ? null : t.getDeadlineAt() != null ? t.getDeadlineAt()
                        : t.getDeadline() == null ? null : t.getDeadline().plusDays(1).atStartOfDay();
            }
            if (from != null && from.plusDays(TRACK_DAYS).isBefore(nowLocal)) {
                r.setFinalAt(now);
                n++;
            }
        }
        return n;
    }

    /** БИН заказчиков тендеров с неотправленными «звонить» — контакты подтягиваются до сообщения. */
    @Transactional(readOnly = true)
    public List<String> pendingCustomerBins(int limit) {
        Set<Long> ids = eventRepo.findUrgentPending().stream().map(TenderResultEvent::getTenderId).collect(Collectors.toCollection(LinkedHashSet::new));
        return tenders.findAllById(ids).stream().map(Tender::getCustomerBin).filter(b -> b != null && b.matches("\\d{12}"))
                .distinct().limit(limit).toList();
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
