package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.DealProgress.Step;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTexts;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Этапы и «Дальше» страницы частной заявки (спека 2026-10-18 §3.1) — поверх этапа PrivateRequestStageCalculator. */
@Component
public class PrivateRequestProgressBuilder {

    private static final Set<String> ANSWERED = Set.of("RESPONDED", "ACCEPTED", "DECLINED");

    private final LeadRepository leads;
    private final PriceRequestRepository priceRequests;
    private final PriceRequestItemRepository items;
    private final InboundEmailRepository inbound;
    private final ClientOfferRepository offers;
    private final DealEventRepository events;

    public PrivateRequestProgressBuilder(LeadRepository leads, PriceRequestRepository priceRequests,
                                         PriceRequestItemRepository items, InboundEmailRepository inbound,
                                         ClientOfferRepository offers, DealEventRepository events) {
        this.leads = leads;
        this.priceRequests = priceRequests;
        this.items = items;
        this.inbound = inbound;
        this.offers = offers;
        this.events = events;
    }

    public DealProgress build(Tender t, PrivateRequestStageCalculator.Summary stage) {
        ZoneId zone = (t.getMarket() == null ? Market.KZ : t.getMarket()).zone();
        String code = stage.code();
        long lines = stage.lineCount();

        Lead lead = leads.findFirstByPrivateRequestId(t.getId()).orElse(null);
        List<PriceRequest> prs = priceRequests.findByTenderId(t.getId());
        Map<Long, Boolean> asked = new HashMap<>();
        for (PriceRequest pr : prs) {
            asked.merge(pr.getDistributor().getId(), ANSWERED.contains(pr.getStatus()), Boolean::logicalOr);
        }
        long answered = asked.values().stream().filter(Boolean::booleanValue).count();
        Map<Long, Instant> bounced = PrivateRequestPricesService.lastBounces(inbound, prs.stream().map(PriceRequest::getId).toList());
        long failed = prs.stream().filter(pr -> PrivateRequestPricesService.bouncedAfterSend(pr, bounced)
                || pr.getLetterStatus() == LetterStatus.NO_EMAIL || pr.getLetterStatus() == LetterStatus.FAILED).count();
        int priced = items.findPricedLots(List.of(t.getId())).size();
        ClientOffer main = mainOffer(offers.findByTenderIdOrderByIdDesc(t.getId()));
        PrivateRequestStatus status = PrivateRequestStatus.of(t.getStatus());
        String closedAt = !status.closed() ? null
                : events.findFirstByTenderIdAndCodeOrderByOccurredAtDescIdDesc(t.getId(), DealEventService.CLOSED)
                        .map(e -> DealTexts.ddMM(e.getOccurredAt(), zone)).orElse(null);

        int phase = switch (code) {
            case "EMPTY" -> 1;
            case "NEW", "REQUESTED" -> 2;
            case "PRICED", "KP_DRAFT", "KP_SENT", "KP_REJECTED" -> 3;
            case "KP_ACCEPTED" -> 4;
            default -> 5;   // WON / LOST / CANCELLED — всё сделано
        };
        String pricesDetail = prs.isEmpty() ? "не запрашивали"
                : priced > 0 ? "цены по " + priced + " из " + lines
                : answered == 0 ? "ответов пока нет"
                : DealTexts.answered(answered, asked.size(), false);
        List<Step> steps = List.of(
                step("SOURCE", 0, phase, lead != null ? "Обращение" : "Создана",
                        lead != null ? DealTexts.channel(lead.getChannel()) + " " + DealTexts.ddMM(lead.getReceivedAt(), zone)
                                : DealTexts.ddMM(t.getCreatedAt(), zone)),
                step("LINES", 1, phase, "Позиции", lines == 0 ? "нет позиций" : DealTexts.positions(lines)),
                step("PRICES", 2, phase, "Цены", pricesDetail),
                step("OFFER", 3, phase, "КП", main == null ? null : "№ " + main.getNumber() + " · " + offerState(main, zone)),
                step("RESULT", 4, phase, "Итог", outcome(status, closedAt)));

        Next next = switch (code) {
            case "EMPTY" -> new Next("Добавьте позиции", "ADD_LINES", null);
            case "NEW" -> new Next("Запросите цены у поставщиков", "REQUEST_PRICES", null);
            case "REQUESTED" -> new Next(DealTexts.answered(answered, asked.size(), true)
                    + (failed > 0 ? "; " + DealTexts.lettersFailed(failed) : ""), "OPEN_PRICES", null);
            case "PRICED" -> new Next("Цены есть по " + priced + " из " + lines + " "
                    + PrivateRequestStageCalculator.positionsGen(lines) + " — можно делать КП", "CREATE_OFFER", null);
            case "KP_DRAFT" -> new Next("Доделайте и отправьте КП № " + main.getNumber(), "OPEN_OFFER", main.getId());
            case "KP_SENT" -> new Next("КП № " + main.getNumber() + " отправлено"
                    + (main.getSentAt() == null ? "" : " " + DealTexts.ddMM(main.getSentAt(), zone))
                    + " — что ответил клиент?", "OFFER_DECISION", main.getId());
            case "KP_REJECTED" -> new Next("Клиент отказался от КП № " + main.getNumber()
                    + " — новое КП или закройте сделку", "CREATE_OFFER", null);
            case "KP_ACCEPTED" -> new Next("Клиент согласился — закройте сделку", "CLOSE_WON", null);
            default -> new Next("Закрыта: " + outcome(status, null) + (closedAt == null ? "" : ", " + closedAt), null, null);
        };
        return new DealProgress(steps, next);
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
