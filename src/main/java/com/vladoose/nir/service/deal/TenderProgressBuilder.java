package com.vladoose.nir.service.deal;

import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.DealProgress.Step;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Этапы и «Дальше» карточки тендера (спека 2026-10-18 §4.1). now — местное время рынка (срок площадки — без пояса). */
@Component
public class TenderProgressBuilder {

    private static final Set<String> ANSWERED = Set.of("RESPONDED", "ACCEPTED", "DECLINED");
    private static final String[] KEYS = {"LOTS", "PRICES", "APPLY", "RESULT", "CONTRACT", "DELIVERY", "PAYMENT"};
    private static final String[] LABELS = {"Лоты и ТЗ", "Цены", "Заявка", "Итог", "Договор", "Поставка", "Оплата"};

    private final PriceRequestRepository priceRequests;
    private final PriceRequestItemRepository items;
    private final ActivityApplyRepository applies;

    public TenderProgressBuilder(PriceRequestRepository priceRequests, PriceRequestItemRepository items,
                                 ActivityApplyRepository applies) {
        this.priceRequests = priceRequests;
        this.items = items;
        this.applies = applies;
    }

    @Transactional(readOnly = true)
    public DealProgress build(Tender t, LocalDateTime now) {
        List<TenderLot> lots = t.getLots();
        boolean imported = t.getPlatform() != null;
        long withTz = lots.stream().filter(l -> l.getTechSpecStatus() == TechSpecStatus.OK).count();
        List<PriceRequest> prs = priceRequests.findByTenderId(t.getId());
        Map<Long, Boolean> asked = new HashMap<>();
        for (PriceRequest pr : prs) {
            Long key = pr.getDistributor() == null ? -pr.getId() : pr.getDistributor().getId();
            asked.merge(key, ANSWERED.contains(pr.getStatus()), Boolean::logicalOr);
        }
        long answered = asked.values().stream().filter(Boolean::booleanValue).count();
        int priced = items.findPricedLots(List.of(t.getId())).size();
        ActivityApply apply = mainApply(applies.findByTenderId(t.getId()));
        String a = apply == null ? null : apply.getStatus();
        boolean cancelled = "CANCELLED".equals(t.getStatus());
        boolean passed = ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now);
        String deadline = t.getDeadlineAt() != null ? DealTexts.ddMMhhmm(t.getDeadlineAt()) : DealTexts.ddMM(t.getDeadline());

        int phase;
        boolean ended = false;   // проиграли / отменено / срок упущен — хвост пропускается
        Next next;
        if ("WON".equals(a)) {
            boolean contract = !blank(apply.getContractNumber()) || apply.getContractSignedAt() != null;
            boolean paid = apply.getPaidAt() != null || "PAID".equals(apply.getDeliveryStatus());
            boolean delivered = paid || apply.getDeliveredAt() != null || "DELIVERED".equals(apply.getDeliveryStatus());
            phase = !contract ? 4 : !delivered ? 5 : !paid ? 6 : 7;
            next = switch (phase) {
                case 4 -> new Next("Выиграли — заключите договор, затем поставка и оплата", "OPEN_APPLY", apply.getId());
                case 5 -> new Next("Договор есть — поставка" + ("ORDERED".equals(apply.getDeliveryStatus()) ? ": заказано" : ""),
                        "OPEN_APPLY", apply.getId());
                case 6 -> new Next("Поставлено — ждём оплату", "OPEN_APPLY", apply.getId());
                default -> new Next("Сделка закрыта: оплачено"
                        + (apply.getPaidAt() == null ? "" : " " + DealTexts.ddMM(apply.getPaidAt())), null, null);
            };
        } else if (cancelled || "LOST".equals(a) || "CANCELLED".equals(a) || "REJECTED".equals(a)) {
            phase = 3;
            ended = true;
            next = new Next(cancelled ? "Тендер отменён на площадке" : "LOST".equals(a) ? "Тендер проигран" : "Заявка отменена",
                    null, null);
        } else if ("SUBMITTED".equals(a) || "UNDER_REVIEW".equals(a)) {
            phase = 3;
            next = new Next("Ждём итога", null, null);
        } else if ("DRAFT".equals(a)) {
            phase = 2;
            ended = passed;
            next = passed ? closedNoApply(deadline)
                    : new Next("Подайте заявку" + (deadline == null ? "" : " — приём до " + deadline), "OPEN_APPLY", apply.getId());
        } else if (prs.isEmpty()) {
            boolean needTz = imported && withTz < lots.size();
            phase = needTz ? 0 : 1;
            ended = passed;
            next = passed ? closedNoApply(deadline)
                    : needTz ? new Next("Разберите ТЗ: " + DealTexts.lots(lots.size() - withTz) + " без ТЗ", "TO_LOTS", null)
                    : new Next("Запросите цены по лотам", "TO_LOTS", null);
        } else if (priced == 0) {
            phase = 1;
            ended = passed;
            next = passed ? closedNoApply(deadline) : new Next(DealTexts.answered(answered, asked.size(), true), "OPEN_REQUESTS", null);
        } else {
            phase = 2;
            ended = passed;
            next = passed ? closedNoApply(deadline)
                    : new Next("Цены есть — выберите победителей и соберите заявку", "OPEN_REQUESTS", null);
        }

        String[] details = {
                imported ? "ТЗ: " + withTz + " из " + lots.size() : DealTexts.lots(lots.size()),
                prs.isEmpty() ? "не запрашивали" : priced > 0 ? "цены по " + priced + " из " + lots.size()
                        : answered == 0 ? "ответов пока нет" : DealTexts.answered(answered, asked.size(), false),
                apply == null ? null : "№ " + apply.getId() + " · " + ApplyStatusLabels.label(a),
                result(a, cancelled),
                contract(apply),
                delivery(apply),
                payment(apply)};
        boolean lostRun = ended && phase == 3;
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) {
            String state = i < phase ? "DONE"
                    : i == phase ? (lostRun ? "DONE" : "CURRENT")
                    : ended ? "SKIPPED" : "TODO";
            steps.add(new Step(KEYS[i], state, LABELS[i], details[i]));
        }
        return new DealProgress(steps, next);
    }

    private static Next closedNoApply(String deadline) {
        return new Next("Приём закрыт" + (deadline == null ? "" : " " + deadline) + " — заявку не подали", null, null);
    }

    /** Последняя не отменённая, иначе последняя. */
    static ActivityApply mainApply(List<ActivityApply> list) {
        List<ActivityApply> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(ActivityApply::getId).reversed());
        return sorted.stream().filter(x -> !"CANCELLED".equals(x.getStatus()) && !"REJECTED".equals(x.getStatus()))
                .findFirst().orElse(sorted.isEmpty() ? null : sorted.get(0));
    }

    private static String result(String a, boolean cancelled) {
        if ("WON".equals(a)) return "выиграли";
        if (cancelled) return "тендер отменён";
        if (a == null) return null;
        return switch (a) {
            case "SUBMITTED", "UNDER_REVIEW" -> "на рассмотрении";
            case "LOST" -> "проиграли";
            case "CANCELLED" -> "отменена";
            case "REJECTED" -> "отклонена";
            default -> null;
        };
    }

    private static String contract(ActivityApply a) {
        if (a == null || (blank(a.getContractNumber()) && a.getContractSignedAt() == null)) return null;
        String num = blank(a.getContractNumber()) ? "подписан" : "№ " + a.getContractNumber().strip();
        return num + (a.getContractSignedAt() == null ? "" : " от " + DealTexts.ddMM(a.getContractSignedAt()));
    }

    private static String delivery(ActivityApply a) {
        if (a == null) return null;
        if (a.getDeliveredAt() != null) return "доставлено " + DealTexts.ddMM(a.getDeliveredAt());
        if ("DELIVERED".equals(a.getDeliveryStatus()) || "PAID".equals(a.getDeliveryStatus())) return "доставлено";
        if ("ORDERED".equals(a.getDeliveryStatus())) return "заказано";
        return null;
    }

    private static String payment(ActivityApply a) {
        if (a == null) return null;
        if (a.getPaidAt() != null) return "оплачено " + DealTexts.ddMM(a.getPaidAt());
        return "PAID".equals(a.getDeliveryStatus()) ? "оплачено" : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
