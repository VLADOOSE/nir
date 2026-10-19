package com.vladoose.nir.service.execution;

import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.DealProgress.Step;
import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.service.execution.ExecutionSnapshot.SupplierState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.service.execution.ExecutionTexts.ddMM;
import static com.vladoose.nir.service.execution.ExecutionTexts.money;

/**
 * Шаги исполнения сделки и «Дальше» (спека 2026-10-19-deal-execution §3) — чистая функция снимка. ACTIVE — заявка в
 * работе с принятым КП: шаги по данным, «Дальше» — первое несделанное по порядку §3.2; PASSIVE — КП ещё не принято
 * (шаги по данным, «Дальше» — не наш); CLOSED — сделка закрыта: несделанное — «–». Подписи шагов без данных
 * исполнения — только в ACTIVE (решение при планировании 3).
 */
public final class DealExecutionCalculator {

    public enum Mode { ACTIVE, PASSIVE, CLOSED }

    /** stageCode / stageText — этап для списка заявок (§3.3); allDone — «Дальше» указывает на Итог. */
    public record Result(List<Step> steps, Next next, String stageCode, String stageText, boolean allDone) {}

    private record Pick(String step, Next next, String code, String chip) {}

    private DealExecutionCalculator() {}

    public static Result evaluate(ExecutionSnapshot s, Mode mode) {
        Pick pick = mode == Mode.ACTIVE ? pick(s) : null;
        String current = pick == null ? null : pick.step();
        boolean details = mode == Mode.ACTIVE || s.hasData();
        List<Step> steps = new ArrayList<>();
        steps.add(step("CONTRACT", "Договор", s.contractDone(), s.contractSkipped(), details ? contractDetail(s) : null, current, mode));
        steps.add(step("PAYMENT", "Оплата", s.paymentDone(), false, details ? paymentDetail(s) : null, current, mode));
        steps.add(step("SUPPLIERS", "Поставщики", s.suppliersDone(), s.fromStock(), details ? suppliersDetail(s) : null, current, mode));
        steps.add(step("DELIVERY", "Поставка", s.deliveryDone(), false,
                s.deliveryDone() ? "поставлено " + ddMM(s.deliveredOn()) : null, current, mode));
        steps.add(step("DOCS", "Документы", s.docsDone(), false,
                s.docsDone() ? "подписаны " + ddMM(s.docsOn()) : null, current, mode));
        if (pick == null) return new Result(steps, null, null, null, false);
        return new Result(steps, pick.next(), pick.code(), pick.chip(), "RESULT".equals(pick.step()));
    }

    private static Step step(String key, String label, boolean done, boolean skipped, String detail, String current, Mode mode) {
        String state = done ? "DONE"
                : skipped || mode == Mode.CLOSED ? "SKIPPED"
                : key.equals(current) ? "CURRENT" : "TODO";
        return new Step(key, state, label, detail);
    }

    /** «Дальше» — первое несделанное по порядку таблицы §3.2. */
    static Pick pick(ExecutionSnapshot s) {
        String cur = s.currency();
        if (!s.contractSkipped() && !s.contractDone()) {
            String text = s.offerLabel() == null ? "Клиент согласился — договор или счёт"
                    : "Клиент согласился на " + s.offerLabel() + " — договор или счёт";
            return new Pick("CONTRACT", new Next(text, "EXEC_CONTRACT", null), "EXEC_CONTRACT", "Договор");
        }
        if (s.prepayPct().signum() > 0) {
            if (!s.amountKnown()) return amount();
            BigDecimal prepay = s.prepayAmount();
            if (s.clientPaid().compareTo(prepay) < 0) {
                boolean full = s.prepayPct().compareTo(ExecutionSnapshot.HUNDRED) >= 0;
                String text = full
                        ? "Ждём оплату: оплачено " + money(s.clientPaid(), null) + " из " + money(s.amount(), cur)
                        : "Ждём предоплату " + ExecutionTexts.pct(s.prepayPct()) + ": оплачено " + money(s.clientPaid(), null)
                          + " из " + money(prepay, cur);
                return new Pick("PAYMENT", new Next(text, "EXEC_PAY_CLIENT", null), "EXEC_PREPAY",
                        full ? "Ждём оплату" : "Ждём предоплату");
            }
        }
        if (!s.fromStock()) {
            List<SupplierState> c = s.counted();
            if (c.isEmpty()) {
                return new Pick("SUPPLIERS", new Next("Добавьте поставщика или отметьте «Со склада»", "EXEC_SUPPLIERS", null),
                        "EXEC_ORDER", "Заказ у поставщиков");
            }
            List<SupplierState> notOrdered = c.stream().filter(x -> x.orderedOn() == null).toList();
            if (!notOrdered.isEmpty()) {
                return supplier("Закажите у " + notOrdered.get(0).name() + more(notOrdered, " (и ещё у "), "EXEC_ORDER",
                        notOrdered, "EXEC_ORDER", "Заказ у поставщиков");
            }
            List<SupplierState> noAmount = c.stream().filter(x -> !x.amountKnown()).toList();
            if (!noAmount.isEmpty()) {
                return supplier("Впишите сумму к оплате " + noAmount.get(0).name() + more(noAmount, " (и ещё "),
                        "EXEC_SUPPLIER_AMOUNT", noAmount, "EXEC_PAY_SUPPLIERS", "Оплата поставщикам");
            }
            List<SupplierState> unpaid = c.stream().filter(x -> !x.paidUp()).toList();
            if (!unpaid.isEmpty()) {
                SupplierState f = unpaid.get(0);
                return supplier("Оплатите " + f.name() + ": " + money(f.paid(), null) + " из " + money(f.amount(), f.currency())
                        + more(unpaid, " (и ещё "), "EXEC_PAY_SUPPLIER", unpaid, "EXEC_PAY_SUPPLIERS", "Оплата поставщикам");
            }
            List<SupplierState> notShipped = c.stream().filter(x -> x.shippedOn() == null).toList();
            if (!notShipped.isEmpty()) {
                return supplier("Ждём отгрузку от " + notShipped.get(0).name() + more(notShipped, " (и ещё от "), "EXEC_SHIP",
                        notShipped, "EXEC_SHIPPING", "Ждём отгрузку");
            }
        }
        if (!s.deliveryDone()) {
            return new Pick("DELIVERY", new Next("Поставьте клиенту", "EXEC_DELIVERY", null), "EXEC_DELIVERY", "Поставка");
        }
        if (!s.docsDone()) {
            return new Pick("DOCS", new Next("Подпишите закрывающие документы (накладная, акт)", "EXEC_DOCS", null),
                    "EXEC_DOCS", "Документы");
        }
        if (!s.amountKnown()) return amount();
        if (!s.paymentDone()) {
            return new Pick("PAYMENT", new Next("Ждём оплату: оплачено " + money(s.clientPaid(), null) + " из "
                    + money(s.amount(), cur), "EXEC_PAY_CLIENT", null), "EXEC_FINAL_PAY", "Ждём оплату");
        }
        return new Pick("RESULT", new Next("Всё сделано — закройте сделку", "CLOSE_WON", null), "EXEC_DONE", "Всё сделано — закрыть");
    }

    private static Pick amount() {
        return new Pick("PAYMENT", new Next("Укажите сумму сделки", "EXEC_AMOUNT", null), "EXEC_AMOUNT", "Сумма сделки?");
    }

    private static Pick supplier(String text, String action, List<SupplierState> list, String code, String chip) {
        return new Pick("SUPPLIERS", new Next(text, action, list.get(0).distributorId()), code, chip);
    }

    private static String more(List<SupplierState> list, String prefix) {
        return list.size() > 1 ? prefix + (list.size() - 1) + ")" : "";
    }

    static String contractDetail(ExecutionSnapshot s) {
        if (s.contractSkipped()) return "без договора";
        if (!s.contractDone()) return null;
        boolean invoice = s.contractKind() == ContractKind.INVOICE;
        String no = s.contractNumber() == null || s.contractNumber().isBlank() ? null : "№ " + s.contractNumber().strip();
        String date = ddMM(s.contractDate());
        if (no == null) return (invoice ? "счёт от " : "подписан ") + date;
        return (invoice ? "счёт " : "") + no + (date == null ? "" : " от " + date);
    }

    static String paymentDetail(ExecutionSnapshot s) {
        if (s.amountKnown()) {
            if (s.paymentDone()) {
                return "оплачено полностью" + (s.lastClientPaidOn() == null ? "" : " " + ddMM(s.lastClientPaidOn()));
            }
            return "оплачено " + money(s.clientPaid(), null) + " из " + money(s.amount(), s.currency());
        }
        return s.clientPaid().signum() > 0 ? "оплачено " + money(s.clientPaid(), s.currency()) : null;
    }

    static String suppliersDetail(ExecutionSnapshot s) {
        if (s.fromStock()) return "со склада";
        List<SupplierState> c = s.counted();
        if (c.isEmpty()) return null;
        long ordered = c.stream().filter(x -> x.orderedOn() != null).count();
        long paid = c.stream().filter(SupplierState::paidUp).count();
        long shipped = c.stream().filter(x -> x.shippedOn() != null).count();
        int n = c.size();
        return "заказ " + ordered + "/" + n + " · оплата " + paid + "/" + n + " · отгрузка " + shipped + "/" + n;
    }
}
