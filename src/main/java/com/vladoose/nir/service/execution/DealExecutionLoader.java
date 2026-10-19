package com.vladoose.nir.service.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.execution.DealExecutionData.MainOffer;
import com.vladoose.nir.service.execution.DealExecutionData.OfferGroup;
import com.vladoose.nir.service.execution.DealExecutionData.SupplierLine;
import com.vladoose.nir.service.execution.ExecutionSnapshot.SupplierState;
import com.vladoose.nir.service.offer.OfferLabels;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.vladoose.nir.service.execution.DealExecutionData.positive;

/**
 * Загрузка исполнения (спека 2026-10-19-deal-execution §6): шесть запросов на любую выборку сделок — страница, список
 * заявок, лента — без запроса на каждую заявку. Поставщики — группы главного принятого КП (в порядке первой строки), затем
 * строки deal_supplier без группы (добавленные вручную) по id; «к оплате по КП» — в валюте рынка и только пока своя
 * сумма пуста.
 */
@Component
public class DealExecutionLoader {

    private static final Comparator<DealPayment> PAYMENT_ORDER =
            Comparator.comparing(DealPayment::getPaidOn).thenComparing(DealPayment::getId);

    private final DealExecutionRepository executions;
    private final DealSupplierRepository suppliers;
    private final DealPaymentRepository payments;
    private final ClientOfferRepository offers;
    private final DistributorRepository distributors;

    public DealExecutionLoader(DealExecutionRepository executions, DealSupplierRepository suppliers,
                               DealPaymentRepository payments, ClientOfferRepository offers, DistributorRepository distributors) {
        this.executions = executions;
        this.suppliers = suppliers;
        this.payments = payments;
        this.offers = offers;
        this.distributors = distributors;
    }

    public DealExecutionData load(Tender t) {
        return load(List.of(t)).get(t.getId());
    }

    public Map<Long, DealExecutionData> load(Collection<Tender> deals) {
        Map<Long, DealExecutionData> out = new HashMap<>();
        if (deals.isEmpty()) return out;
        List<Long> ids = deals.stream().map(Tender::getId).toList();

        Map<Long, DealExecution> rows = new HashMap<>();
        for (DealExecution e : executions.findByTenderIdIn(ids)) rows.put(e.getTenderId(), e);
        Map<Long, List<DealSupplier>> supplierRows = new HashMap<>();
        for (DealSupplier s : suppliers.findByTenderIdIn(ids)) {
            supplierRows.computeIfAbsent(s.getTenderId(), k -> new ArrayList<>()).add(s);
        }
        Map<Long, List<DealPayment>> paymentRows = new HashMap<>();
        for (DealPayment p : payments.findByTenderIdIn(ids)) {
            paymentRows.computeIfAbsent(p.getTenderId(), k -> new ArrayList<>()).add(p);
        }
        Map<Long, MainOffer> main = new HashMap<>();
        for (Object[] r : offers.findAcceptedByTenderIds(ids, ClientOfferStatus.ACCEPTED)) {   // новые сверху
            main.putIfAbsent((Long) r[0], new MainOffer((Long) r[1], (BigDecimal) r[2], OfferLabels.of((Integer) r[3], (LocalDate) r[4])));
        }
        Map<Long, List<Object[]>> offerLines = new HashMap<>();
        if (!main.isEmpty()) {
            List<Long> offerIds = main.values().stream().map(MainOffer::id).toList();
            for (Object[] r : offers.findPurchaseRows(offerIds, ClientOfferItemKind.ITEM)) {
                offerLines.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(r);
            }
        }
        Set<Long> distributorIds = new HashSet<>();
        supplierRows.values().forEach(l -> l.forEach(s -> distributorIds.add(s.getDistributorId())));
        offerLines.values().forEach(l -> l.forEach(r -> { if (r[1] != null) distributorIds.add((Long) r[1]); }));
        Map<Long, String> names = new HashMap<>();
        if (!distributorIds.isEmpty()) {
            for (Object[] r : distributors.findNames(distributorIds)) names.put((Long) r[0], (String) r[1]);
        }

        for (Tender t : deals) {
            Market market = t.getMarket() == null ? MarketContext.get() : t.getMarket();
            MainOffer m = main.get(t.getId());
            out.put(t.getId(), build(t.getId(), market.currencyCode(), rows.get(t.getId()), m,
                    m == null ? List.of() : offerLines.getOrDefault(m.id(), List.of()),
                    supplierRows.getOrDefault(t.getId(), List.of()), paymentRows.getOrDefault(t.getId(), List.of()), names));
        }
        return out;
    }

    static DealExecutionData build(Long tenderId, String currency, DealExecution row, MainOffer main, List<Object[]> lines,
                                   List<DealSupplier> rows, List<DealPayment> payments, Map<Long, String> names) {
        // группы КП: сумма закупок по строке до тиына (как cost_total в расчёте КП), только строки с закупкой
        Map<Long, BigDecimal> purchase = new HashMap<>();
        Map<Long, Integer> firstLine = new HashMap<>();
        int unassigned = 0;
        BigDecimal unassignedSum = null;
        List<Object[]> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingInt(r -> (Integer) r[4]));
        for (Object[] r : sorted) {
            Long d = (Long) r[1];
            BigDecimal p = (BigDecimal) r[2], q = (BigDecimal) r[3];
            BigDecimal cost = p == null || q == null ? null : p.multiply(q).setScale(2, RoundingMode.HALF_UP);
            if (d == null) {
                unassigned++;
                if (cost != null) unassignedSum = unassignedSum == null ? cost : unassignedSum.add(cost);
                continue;
            }
            firstLine.putIfAbsent(d, (Integer) r[4]);
            if (cost != null) purchase.merge(d, cost, BigDecimal::add);
        }
        List<OfferGroup> groups = firstLine.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .map(e -> new OfferGroup(e.getKey(), positive(purchase.get(e.getKey())), e.getValue())).toList();

        List<DealPayment> client = new ArrayList<>();
        Map<Long, List<DealPayment>> bySupplier = new HashMap<>();
        for (DealPayment p : payments) {
            if (p.getDealSupplierId() == null) client.add(p);
            else bySupplier.computeIfAbsent(p.getDealSupplierId(), k -> new ArrayList<>()).add(p);
        }
        client.sort(PAYMENT_ORDER);
        bySupplier.values().forEach(l -> l.sort(PAYMENT_ORDER));

        Map<Long, DealSupplier> byDistributor = new HashMap<>();
        for (DealSupplier s : rows) byDistributor.put(s.getDistributorId(), s);
        List<SupplierLine> supplierLines = new ArrayList<>();
        for (OfferGroup g : groups) {
            supplierLines.add(line(byDistributor.remove(g.distributorId()), g, g.distributorId(), currency, bySupplier, names));
        }
        byDistributor.values().stream().sorted(Comparator.comparing(DealSupplier::getId))
                .forEach(s -> supplierLines.add(line(s, null, s.getDistributorId(), currency, bySupplier, names)));

        BigDecimal clientPaid = client.stream().map(DealPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate lastPaid = client.stream().map(DealPayment::getPaidOn).max(Comparator.naturalOrder()).orElse(null);
        BigDecimal amount = row != null && row.getAmount() != null ? row.getAmount() : main == null ? null : positive(main.total());
        boolean hasData = row != null || !rows.isEmpty() || !payments.isEmpty();
        ExecutionSnapshot snapshot = ExecutionSnapshot.builder()
                .currency(currency)
                .offerLabel(main == null ? null : main.label())
                .amount(amount)
                .contractKind(row == null ? null : row.getContractKind())
                .contractNumber(row == null ? null : row.getContractNumber())
                .contractDate(row == null ? null : row.getContractDate())
                .prepayPct(row == null ? null : row.getPrepayPct())
                .clientPaid(clientPaid)
                .lastClientPaidOn(lastPaid)
                .fromStock(row != null && row.isFromStock())
                .suppliers(supplierLines.stream().map(l -> new SupplierState(l.distributorId(), l.name(), l.excluded(),
                        l.currency(), l.amount(), l.orderedOn(), l.paid(), l.shippedOn())).toList())
                .deliveredOn(row == null ? null : row.getDeliveredOn())
                .docsOn(row == null ? null : row.getDocsOn())
                .hasData(hasData)
                .build();
        return new DealExecutionData(tenderId, currency, row, main, groups, unassigned, unassignedSum, supplierLines, client, snapshot);
    }

    private static SupplierLine line(DealSupplier r, OfferGroup g, Long distributorId, String marketCurrency,
                                     Map<Long, List<DealPayment>> bySupplier, Map<Long, String> names) {
        String cur = r == null ? marketCurrency : r.getCurrency();
        BigDecimal offerAmount = g == null ? null : g.purchase();
        boolean own = r != null && r.getAmount() != null;
        BigDecimal amount = own ? r.getAmount() : cur.equals(marketCurrency) ? offerAmount : null;
        List<DealPayment> pays = r == null ? List.of() : bySupplier.getOrDefault(r.getId(), List.of());
        BigDecimal paid = pays.stream().map(DealPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SupplierLine(r == null ? null : r.getId(), distributorId, names.getOrDefault(distributorId, "Поставщик"),
                g != null, r != null && r.isManual(), r != null && r.isExcluded(), cur, amount, !own && amount != null,
                offerAmount, r == null ? null : r.getOrderedOn(), r == null ? null : r.getOrderNote(), paid, pays,
                r == null ? null : r.getShippedOn(), r == null ? null : r.getShipNote());
    }
}
