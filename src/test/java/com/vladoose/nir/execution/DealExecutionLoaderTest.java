package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionData;
import com.vladoose.nir.service.execution.DealExecutionData.SupplierLine;
import com.vladoose.nir.service.execution.DealExecutionLoader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;

/** Загрузка исполнения (спека 2026-10-19-deal-execution §6): главное принятое КП, поставщики КП ∪ строки, снимок. */
@SpringBootTest
@Transactional
class DealExecutionLoaderTest {

    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DealPaymentRepository payments;
    @Autowired DealExecutionRepository executions;
    @Autowired DealExecutionLoader loader;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name) {
        return distributors.save(Distributor.builder().name("ZZ " + name + " " + System.nanoTime()).build());
    }

    private DealExecutionData load(Tender t) {
        em.flush();
        em.clear();
        return loader.load(requests.findById(t.getId()));
    }

    private static DealPayment pay(Tender t, Long supplierRow, String amount, LocalDate on) {
        return DealPayment.builder().tenderId(t.getId()).dealSupplierId(supplierRow).amount(new BigDecimal(amount)).paidOn(on).build();
    }

    @Test
    void suppliersFromMainAcceptedOffer_perLineRounding_unassignedHint() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК"), med = dist("MEDSYST");
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "100", 1), fotek.getId(), "100", "1"));   // прежнее принятое
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "3070455.00", 2);
        line(o, med.getId(), "57000", "2");      // 114 000
        line(o, fotek.getId(), "100.01", "0.5"); // 50,005 → 50,01 (закупка — NUMERIC(15,2), дробь — в количестве)
        line(o, fotek.getId(), "100.01", "0.5"); // ещё 50,01: по строке — 100,02, разом было бы 100,01
        line(o, fotek.getId(), null, "1");       // без закупки — не в сумме
        line(o, null, "30000", "1");             // без поставщика
        o = offers.save(o);
        offers.save(line(offer(t, ClientOfferStatus.SENT, "999", 3), med.getId(), "1", "1"));   // не принятое

        DealExecutionData d = load(t);
        assertThat(d.mainOffer().id()).isEqualTo(o.getId());
        assertThat(d.mainOffer().label()).isEqualTo("КП № 2");
        assertThat(d.supplierLines()).extracting(SupplierLine::distributorId).containsExactly(med.getId(), fotek.getId());
        assertThat(d.supplierLines().get(0).amount()).isEqualByComparingTo("114000");
        assertThat(d.supplierLines().get(1).amount()).isEqualByComparingTo("100.02");
        assertThat(d.supplierLines().get(1).amountFromOffer()).isTrue();
        assertThat(d.supplierLines().get(1).fromOffer()).isTrue();
        assertThat(d.supplierLines().get(1).rowId()).isNull();
        assertThat(d.unassignedCount()).isEqualTo(1);
        assertThat(d.unassignedPurchase()).isEqualByComparingTo("30000");
        assertThat(d.snapshot().amount()).isEqualByComparingTo("3070455");
        assertThat(d.snapshot().offerLabel()).isEqualTo("КП № 2");
        assertThat(d.snapshot().hasData()).isFalse();
        assertThat(d.visible()).isTrue();
        assertThat(d.inOffer(fotek.getId())).isTrue();
    }

    @Test
    void offerEditedAfterAcceptance_amountsFollowUntilOwn() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК");
        ClientOffer o = offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "500", 1), fotek.getId(), "100", "1"));
        assertThat(load(t).supplierLines().get(0).amount()).isEqualByComparingTo("100");

        ClientOffer back = offers.findById(o.getId()).orElseThrow();
        back.getItems().get(0).setPurchasePrice(new BigDecimal("120"));
        back.setTotalAmount(new BigDecimal("600"));
        DealExecutionData d = load(t);
        assertThat(d.supplierLines().get(0).amount()).isEqualByComparingTo("120");
        assertThat(d.snapshot().amount()).isEqualByComparingTo("600");

        suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(fotek.getId()).currency("KZT")
                .amount(new BigDecimal("90")).build());
        executions.save(DealExecution.builder().tenderId(t.getId()).amount(new BigDecimal("650")).build());
        d = load(t);
        assertThat(d.supplierLines().get(0).amount()).isEqualByComparingTo("90");
        assertThat(d.supplierLines().get(0).amountFromOffer()).isFalse();
        assertThat(d.supplierLines().get(0).offerAmount()).isEqualByComparingTo("120");
        assertThat(d.snapshot().amount()).isEqualByComparingTo("650");
        assertThat(d.snapshot().hasData()).isTrue();
    }

    @Test
    void foreignCurrencyWithoutOwnAmount_unknown() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК");
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "500", 1), fotek.getId(), "100", "1"));
        suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(fotek.getId()).currency("RUB").build());
        SupplierLine s = load(t).supplierLines().get(0);
        assertThat(s.amount()).isNull();
        assertThat(s.offerAmount()).isEqualByComparingTo("100");
        assertThat(s.currency()).isEqualTo("RUB");
    }

    @Test
    void manualSupplierAfterOffer_paymentsSplitClientAndSupplier() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК"), rm = dist("Регион-Мед");
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "500", 1), fotek.getId(), "100", "1"));
        DealSupplier manual = suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(rm.getId())
                .currency("RUB").amount(new BigDecimal("277025")).manual(true).build());
        payments.save(pay(t, null, "200", LocalDate.of(2026, 10, 20)));
        payments.save(pay(t, null, "100", LocalDate.of(2026, 10, 21)));
        payments.save(pay(t, manual.getId(), "277025", LocalDate.of(2026, 10, 21)));

        DealExecutionData d = load(t);
        assertThat(d.supplierLines()).extracting(SupplierLine::name).containsExactly(fotek.getName(), rm.getName());
        assertThat(d.supplierLines().get(1).manual()).isTrue();
        assertThat(d.supplierLines().get(1).fromOffer()).isFalse();
        assertThat(d.supplierLines().get(1).paid()).isEqualByComparingTo("277025");
        assertThat(d.clientPayments()).extracting(DealPayment::getAmount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("200"), new BigDecimal("100"));
        assertThat(d.snapshot().clientPaid()).isEqualByComparingTo("300");
        assertThat(d.snapshot().lastClientPaidOn()).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void noAcceptedOffer_hiddenUntilData_zeroTotalUnknown() {
        Tender t = requests.createEmpty();
        offers.save(offer(t, ClientOfferStatus.SENT, "500", 1));
        assertThat(load(t).visible()).isFalse();
        payments.save(pay(t, null, "10", LocalDate.of(2026, 10, 20)));
        assertThat(load(t).visible()).isTrue();

        Tender z = requests.createEmpty();
        offers.save(offer(z, ClientOfferStatus.ACCEPTED, "0", 2));
        assertThat(load(z).snapshot().amount()).isNull();
    }

    @Test
    void batchLoadDoesNotGrowWithDeals() {
        List<Tender> two = deals(2), six = deals(6);
        assertThat(statements(six)).isEqualTo(statements(two));
    }

    private List<Tender> deals(int n) {
        List<Tender> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Tender t = requests.createEmpty();
            Distributor d = dist("Пачка");
            offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "100", 10 + i), d.getId(), "10", "1"));
            suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(d.getId()).currency("KZT").build());
            payments.save(pay(t, null, "5", LocalDate.of(2026, 10, 20)));
            out.add(t);
        }
        return out;
    }

    private long statements(List<Tender> deals) {
        em.flush();
        em.clear();
        List<Tender> fresh = deals.stream().map(t -> requests.findById(t.getId())).toList();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean was = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            loader.load(fresh);
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(was);
        }
    }
}
