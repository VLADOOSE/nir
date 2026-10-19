package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.DealProgress.Step;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTexts;
import com.vladoose.nir.service.execution.DealExecutionCalculator;
import com.vladoose.nir.service.execution.DealExecutionData;
import com.vladoose.nir.service.execution.DealExecutionLoader;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.service.readyoffer.ReadyOfferHints;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Этапы и «Дальше» страницы частной заявки (спека 2026-10-18 §3.1) и исполнение сделки (спека 2026-10-19-deal-execution §3)
 * — поверх этапа PrivateRequestStageCalculator.
 */
@Component
public class PrivateRequestProgressBuilder {

    private static final Set<String> ANSWERED = Set.of("RESPONDED", "ACCEPTED", "DECLINED");

    private final LeadRepository leads;
    private final PriceRequestRepository priceRequests;
    private final PriceRequestItemRepository items;
    private final InboundEmailRepository inbound;
    private final ClientOfferRepository offers;
    private final DealEventRepository events;
    private final ReadyOfferHints hints;
    private final DealExecutionLoader execution;

    public PrivateRequestProgressBuilder(LeadRepository leads, PriceRequestRepository priceRequests,
                                         PriceRequestItemRepository items, InboundEmailRepository inbound,
                                         ClientOfferRepository offers, DealEventRepository events, ReadyOfferHints hints,
                                         DealExecutionLoader execution) {
        this.leads = leads;
        this.priceRequests = priceRequests;
        this.items = items;
        this.inbound = inbound;
        this.offers = offers;
        this.events = events;
        this.hints = hints;
        this.execution = execution;
    }

    public DealProgress build(Tender t, PrivateRequestStageCalculator.Summary stage) {
        ZoneId zone = (t.getMarket() == null ? Market.KZ : t.getMarket()).zone();
        String code = stage.code();
        long lines = stage.lineCount();
        DealExecutionData exec = stage.exec() != null ? stage.exec() : execution.load(t);
        boolean executing = code.startsWith("EXEC_");

        Lead lead = leads.findFirstByPrivateRequestId(t.getId()).orElse(null);
        List<PriceRequest> prs = priceRequests.findForDealByTenderId(t.getId());
        Map<Long, Boolean> asked = new HashMap<>();
        for (PriceRequest pr : prs) {
            asked.merge(pr.getDistributor().getId(), ANSWERED.contains(pr.getStatus()), Boolean::logicalOr);
        }
        long answered = asked.values().stream().filter(Boolean::booleanValue).count();
        Map<Long, Instant> bounced = PrivateRequestPricesService.lastBounces(inbound, prs.stream().map(PriceRequest::getId).toList());
        long failed = prs.stream().filter(pr -> PrivateRequestPricesService.bouncedAfterSend(pr, bounced)
                || pr.getLetterStatus() == LetterStatus.NO_EMAIL || pr.getLetterStatus() == LetterStatus.FAILED).count();
        int priced = items.findPricedLots(List.of(t.getId())).size();
        List<ClientOffer> allOffers = offers.findByTenderIdOrderByIdDesc(t.getId());
        ClientOffer main = mainOffer(allOffers);
        PrivateRequestStatus status = PrivateRequestStatus.of(t.getStatus());
        String closedAt = !status.closed() ? null
                : events.findFirstByTenderIdAndCodeOrderByOccurredAtDescIdDesc(t.getId(), DealEventService.CLOSED)
                        .map(e -> DealTexts.ddMM(e.getOccurredAt(), zone)).orElse(null);

        int phase = switch (code) {
            case "EMPTY" -> 1;
            case "NEW", "REQUESTED" -> 2;
            case "PRICED", "KP_DRAFT", "KP_SENT", "KP_REJECTED" -> 3;
            default -> 4;   // исполнение (EXEC_*) и закрытая сделка: Обращение … КП — сделаны
        };
        DealExecutionCalculator.Mode mode = executing ? DealExecutionCalculator.Mode.ACTIVE
                : status.closed() ? DealExecutionCalculator.Mode.CLOSED : DealExecutionCalculator.Mode.PASSIVE;
        DealExecutionCalculator.Result ex = DealExecutionCalculator.evaluate(exec.snapshot(), mode);
        String pricesDetail = prs.isEmpty() ? "не запрашивали"
                : priced > 0 ? "цены по " + priced + " из " + lines
                : answered == 0 ? "ответов пока нет"
                : DealTexts.answered(answered, asked.size(), false);
        List<Step> steps = new ArrayList<>(List.of(
                step("SOURCE", 0, phase, lead != null ? "Обращение" : "Создана",
                        lead != null ? DealTexts.channel(lead.getChannel()) + " " + DealTexts.ddMM(lead.getReceivedAt(), zone)
                                : DealTexts.ddMM(t.getCreatedAt(), zone)),
                step("LINES", 1, phase, "Позиции", lines == 0 ? "нет позиций" : DealTexts.positions(lines)),
                step("PRICES", 2, phase, "Цены", pricesDetail),
                step("OFFER", 3, phase, "КП", main == null ? null : OfferLabels.numberOrDate(main) + " · " + offerState(main, zone))));
        steps.addAll(ex.steps());
        steps.add(new Step("RESULT", status.closed() ? "DONE" : ex.allDone() ? "CURRENT" : "TODO", "Итог", outcome(status, closedAt)));

        Next next = executing ? ex.next() : switch (code) {
            case "EMPTY" -> new Next("Добавьте позиции", "ADD_LINES", null);
            case "NEW" -> new Next("Запросите цены у поставщиков", "REQUEST_PRICES", null);
            case "REQUESTED" -> new Next(DealTexts.answered(answered, asked.size(), true)
                    + (failed > 0 ? "; " + DealTexts.lettersFailed(failed) : ""), "OPEN_PRICES", null);
            case "PRICED" -> new Next("Цены есть по " + priced + " из " + lines + " "
                    + PrivateRequestStageCalculator.positionsGen(lines) + " — можно делать КП", "CREATE_OFFER", null);
            case "KP_DRAFT" -> new Next("Доделайте и отправьте " + OfferLabels.of(main), "OPEN_OFFER", main.getId());
            case "KP_SENT" -> new Next(OfferLabels.of(main) + " отправлено"
                    + (main.getSentAt() == null ? "" : " " + DealTexts.ddMM(main.getSentAt(), zone))
                    + " — что ответил клиент?", "OFFER_DECISION", main.getId());
            case "KP_REJECTED" -> new Next("Клиент отказался от " + OfferLabels.of(main)
                    + " — новое КП или закройте сделку", "CREATE_OFFER", null);
            default -> new Next("Закрыта: " + outcome(status, null) + (closedAt == null ? "" : ", " + closedAt), null, null);
        };
        // подсказка «КП ушло клиенту» (спека 2026-10-19 §3.4) важнее обычного «Дальше»
        Next hint = hints.hint(t, code, allOffers).orElse(null);
        return new DealProgress(steps, hint != null ? hint : next);
    }

    private static Step step(String key, int index, int phase, String label, String detail) {
        String state = index < phase ? "DONE" : index == phase ? "CURRENT" : "TODO";
        return new Step(key, state, label, detail);
    }

    /** Главное КП: принятое → последнее отправленное → последний черновик → последнее (отклонённое). */
    static ClientOffer mainOffer(List<ClientOffer> newestFirst) {
        for (ClientOfferStatus s : List.of(ClientOfferStatus.ACCEPTED, ClientOfferStatus.SENT, ClientOfferStatus.DRAFT)) {
            for (ClientOffer o : newestFirst) if (o.getStatus() == s) return o;
        }
        return newestFirst.isEmpty() ? null : newestFirst.get(0);
    }

    private static String offerState(ClientOffer o, ZoneId zone) {
        return switch (o.getStatus()) {
            case DRAFT -> "черновик";
            case SENT -> "отправлено" + (o.getSentAt() == null ? "" : " " + DealTexts.ddMM(o.getSentAt(), zone));
            case ACCEPTED -> "принято";
            case REJECTED -> "отклонено";
        };
    }

    private static String outcome(PrivateRequestStatus status, String closedAt) {
        String text = switch (status) {
            case WON -> "купили у нас";
            case LOST -> "не купили";
            case CANCELLED -> "отменена";
            case NEW -> null;
        };
        return text == null || closedAt == null ? text : text + " " + closedAt;
    }
}
