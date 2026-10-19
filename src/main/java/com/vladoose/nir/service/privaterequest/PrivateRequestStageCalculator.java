package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.entity.ClientOfferStatus;
import com.vladoose.nir.entity.PrivateRequestStatus;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.execution.DealExecutionCalculator;
import com.vladoose.nir.service.execution.DealExecutionData;
import com.vladoose.nir.service.execution.DealExecutionLoader;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Этап частной заявки — считается из данных при каждом чтении (спека §5.4), сверху вниз: закрыта → КП клиенту (по лучшему
 * статусу; принятое — этап исполнения {@code EXEC_*}, спека 2026-10-19-deal-execution §3.3) → есть цены → запрошены цены →
 * новая → пустая. Четыре запроса на весь список — без N+1; шесть запросов исполнения — {@link DealExecutionLoader}.
 */
@Component
public class PrivateRequestStageCalculator {

    /** exec — исполнение сделки (тот же снимок дальше строит шаги, вид раздела и ленту — одна загрузка на страницу). */
    public record Summary(String code, String text, long lineCount, DealExecutionData exec) {
        public Summary(String code, String text, long lineCount) {
            this(code, text, lineCount, null);
        }
    }

    /** Поставщик «ответил», если хоть один его запрос по заявке получил ответ или отказ. */
    private static final Set<String> ANSWERED = Set.of("RESPONDED", "ACCEPTED", "DECLINED");

    private final TenderLotRepository lotRepository;
    private final PriceRequestRepository priceRequestRepository;
    private final PriceRequestItemRepository itemRepository;
    private final ClientOfferRepository clientOffers;
    private final DealExecutionLoader execution;

    public PrivateRequestStageCalculator(TenderLotRepository lotRepository, PriceRequestRepository priceRequestRepository,
                                         PriceRequestItemRepository itemRepository, ClientOfferRepository clientOffers,
                                         DealExecutionLoader execution) {
        this.lotRepository = lotRepository;
        this.priceRequestRepository = priceRequestRepository;
        this.itemRepository = itemRepository;
        this.clientOffers = clientOffers;
        this.execution = execution;
    }

    public Summary of(Tender t) {
        return of(List.of(t)).get(t.getId());
    }

    public Map<Long, Summary> of(List<Tender> requests) {
        Map<Long, Summary> out = new HashMap<>();
        if (requests.isEmpty()) return out;
        List<Long> ids = requests.stream().map(Tender::getId).toList();

        Map<Long, Long> lines = new HashMap<>();
        for (Object[] row : lotRepository.countByTenderIds(ids)) lines.put((Long) row[0], (Long) row[1]);

        Map<Long, Map<Long, Boolean>> asked = new HashMap<>();   // заявка → поставщик → ответил/отказал
        for (Object[] row : priceRequestRepository.findStageRows(ids)) {
            asked.computeIfAbsent((Long) row[0], k -> new HashMap<>())
                    .merge((Long) row[1], ANSWERED.contains((String) row[2]), Boolean::logicalOr);
        }

        Map<Long, Set<Long>> priced = new HashMap<>();
        for (Object[] row : itemRepository.findPricedLots(ids)) {
            priced.computeIfAbsent((Long) row[0], k -> new HashSet<>()).add((Long) row[1]);
        }

        Map<Long, Set<ClientOfferStatus>> kp = new HashMap<>();   // заявка → статусы её КП
        for (Object[] row : clientOffers.findStatusesByTenderIds(ids)) {
            kp.computeIfAbsent((Long) row[0], k -> EnumSet.noneOf(ClientOfferStatus.class)).add((ClientOfferStatus) row[1]);
        }

        Map<Long, DealExecutionData> exec = execution.load(requests);
        for (Tender t : requests) {
            long lineCount = lines.getOrDefault(t.getId(), 0L);
            Summary s = summary(PrivateRequestStatus.of(t.getStatus()), lineCount,
                    asked.getOrDefault(t.getId(), Map.of()), priced.getOrDefault(t.getId(), Set.of()).size(),
                    kp.getOrDefault(t.getId(), Set.of()));
            DealExecutionData d = exec.get(t.getId());
            if ("KP_ACCEPTED".equals(s.code())) {
                // клиент согласился, заявка в работе — этап исполнения (спека 2026-10-19-deal-execution §3.3)
                DealExecutionCalculator.Result r = DealExecutionCalculator.evaluate(d.snapshot(), DealExecutionCalculator.Mode.ACTIVE);
                s = new Summary(r.stageCode(), r.stageText(), lineCount, d);
            } else {
                s = new Summary(s.code(), s.text(), s.lineCount(), d);
            }
            out.put(t.getId(), s);
        }
        return out;
    }

    static Summary summary(PrivateRequestStatus status, long lines, Map<Long, Boolean> asked, int pricedLines,
                           Set<ClientOfferStatus> offers) {
        if (status == PrivateRequestStatus.WON) return new Summary("WON", "Закрыта: купили у нас", lines);
        if (status == PrivateRequestStatus.LOST) return new Summary("LOST", "Закрыта: не купили", lines);
        if (status == PrivateRequestStatus.CANCELLED) return new Summary("CANCELLED", "Закрыта: отменена", lines);
        if (!offers.isEmpty()) {   // правило 2 (спека §5.4): по лучшему статусу КП
            if (offers.contains(ClientOfferStatus.ACCEPTED)) return new Summary("KP_ACCEPTED", "КП принято", lines);
            if (offers.contains(ClientOfferStatus.SENT)) return new Summary("KP_SENT", "КП отправлено", lines);
            if (offers.contains(ClientOfferStatus.DRAFT)) return new Summary("KP_DRAFT", "КП — черновик", lines);
            return new Summary("KP_REJECTED", "КП отклонено", lines);
        }
        if (pricedLines > 0) {
            return new Summary("PRICED", "Есть цены: " + pricedLines + " из " + lines + " " + positionsGen(lines), lines);
        }
        if (!asked.isEmpty()) {
            long answered = asked.values().stream().filter(Boolean::booleanValue).count();
            return new Summary("REQUESTED",
                    "Запрошены цены · " + answered + " из " + asked.size() + " " + answeredWord(answered), lines);
        }
        if (lines > 0) return new Summary("NEW", "Новая", lines);
        return new Summary("EMPTY", "Пустая", lines);
    }

    /** «из 1 позиции», «из 2 позиций», «из 11 позиций», «из 21 позиции». */
    public static String positionsGen(long n) {
        return n % 10 == 1 && n % 100 != 11 ? "позиции" : "позиций";
    }

    /** «1 из 3 ответил», «2 из 5 ответили», «11 из 12 ответили». */
    public static String answeredWord(long n) {
        return n % 10 == 1 && n % 100 != 11 ? "ответил" : "ответили";
    }
}
