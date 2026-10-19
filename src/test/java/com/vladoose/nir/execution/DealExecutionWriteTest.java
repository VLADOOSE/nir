package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.ExecutionMark;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.execution.DealExecutionService.PaymentInput;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Запись исполнения (спека 2026-10-19-deal-execution §7, §9): поля договора, отметки, платежи; гард и отказ без записи. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class DealExecutionWriteTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealExecutionRepository executions;
    @Autowired DealPaymentRepository payments;
    @Autowired DealSupplierRepository suppliers;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.now(Market.KZ.zone());

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist() {
        return distributors.save(Distributor.builder().name("ZZ Запись " + System.nanoTime()).build());
    }

    private Tender accepted(Distributor... ds) {
        Tender t = requests.createEmpty();
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "1000", 1);
        for (Distributor d : ds) line(o, d.getId(), "100", "1");
        offers.save(o);
        return t;
    }

    private DealExecution row(Tender t) {
        em.flush();
        em.clear();
        return executions.findById(t.getId()).orElseThrow();
    }

    @Test
    void contractFields_saved_andReset() {
        Tender t = accepted();
        service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "INVOICE");
        service.updateField(t.getId(), ExecutionField.CONTRACT_NUMBER, " 15 ");
        service.updateField(t.getId(), ExecutionField.CONTRACT_DATE, DAY.toString());
        service.updateField(t.getId(), ExecutionField.PREPAY_PCT, "50");
        service.updateField(t.getId(), ExecutionField.AMOUNT, "3 200 000");
        DealExecution e = row(t);
        assertThat(e.getContractKind()).isEqualTo(ContractKind.INVOICE);
        assertThat(e.getContractNumber()).isEqualTo("15");
        assertThat(e.getContractDate()).isEqualTo(DAY);
        assertThat(e.getPrepayPct()).isEqualByComparingTo("50");
        assertThat(e.getAmount()).isEqualByComparingTo("3200000");
        assertThat(e.getUpdatedBy()).isEqualTo("vlad");

        service.updateField(t.getId(), ExecutionField.AMOUNT, "");        // вернуть по КП
        service.updateField(t.getId(), ExecutionField.PREPAY_PCT, null);  // по умолчанию — 100 %
        e = row(t);
        assertThat(e.getAmount()).isNull();
        assertThat(e.getPrepayPct()).isEqualByComparingTo("100");
    }

    @Test
    void rejectedInputWritesNothing() {
        Tender t = accepted();
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.CONTRACT_DATE, DAY.plusDays(1).toString()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("ещё не наступила");
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.AMOUNT, "-1"))
                .isInstanceOf(BadRequestException.class).hasMessage("Сумма сделки: сумма должна быть больше нуля");
        em.flush();
        assertThat(executions.findById(t.getId())).isEmpty();
    }

    @Test
    void marks_setAndClear() {
        Tender t = accepted();
        service.setMark(t.getId(), ExecutionMark.DELIVERY, DAY.toString(), "обучение проведено");
        service.setMark(t.getId(), ExecutionMark.DOCS, DAY.toString(), null);
        DealExecution e = row(t);
        assertThat(e.getDeliveredOn()).isEqualTo(DAY);
        assertThat(e.getDeliveryNote()).isEqualTo("обучение проведено");
        assertThat(e.getDocsOn()).isEqualTo(DAY);

        service.setMark(t.getId(), ExecutionMark.DELIVERY, null, "что-то");   // снять — заметка уходит вместе с датой
        e = row(t);
        assertThat(e.getDeliveredOn()).isNull();
        assertThat(e.getDeliveryNote()).isNull();
    }

    @Test
    void clientPayment_addUpdateDelete() {
        Tender t = accepted();
        service.addPayment(t.getId(), new PaymentInput(null, new BigDecimal("1535227.50"), DAY, " предоплата "));
        em.flush();
        DealPayment p = payments.findByTenderIdIn(List.of(t.getId())).get(0);
        assertThat(p.getNote()).isEqualTo("предоплата");
        assertThat(p.getDealSupplierId()).isNull();
        assertThat(p.getCreatedBy()).isEqualTo("vlad");

        service.updatePayment(t.getId(), p.getId(), new PaymentInput(null, new BigDecimal("1535228"), DAY, null));
        em.flush();
        em.clear();
        assertThat(payments.findById(p.getId()).orElseThrow().getAmount()).isEqualByComparingTo("1535228");

        service.deletePayment(t.getId(), p.getId());
        em.flush();
        assertThat(payments.findByTenderIdIn(List.of(t.getId()))).isEmpty();
    }

    @Test
    void supplierPayment_createsRowForOfferSupplier_strangerIs404() {
        Distributor fotek = dist();
        Tender t = accepted(fotek);
        service.addPayment(t.getId(), new PaymentInput(fotek.getId(), new BigDecimal("100"), DAY, null));
        em.flush();
        DealSupplier s = suppliers.findByTenderIdAndDistributorId(t.getId(), fotek.getId()).orElseThrow();
        assertThat(s.getCurrency()).isEqualTo("KZT");
        assertThat(s.isManual()).isFalse();

        Distributor stranger = dist();
        assertThatThrownBy(() -> service.addPayment(t.getId(), new PaymentInput(stranger.getId(), BigDecimal.TEN, DAY, null)))
                .isInstanceOf(NotFoundException.class).hasMessage("Поставщика нет в сделке");
    }

    @Test
    void paymentOfOtherDeal_404() {
        Tender a = accepted(), b = accepted();
        service.addPayment(a.getId(), new PaymentInput(null, BigDecimal.TEN, DAY, null));
        em.flush();
        Long pid = payments.findByTenderIdIn(List.of(a.getId())).get(0).getId();
        assertThatThrownBy(() -> service.deletePayment(b.getId(), pid)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updatePayment(b.getId(), pid, new PaymentInput(null, BigDecimal.ONE, DAY, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void otherMarketDeal_404() {
        Tender t = accepted();
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.CONTRACT_NUMBER, "1"))
                .isInstanceOf(NotFoundException.class);
    }
}
