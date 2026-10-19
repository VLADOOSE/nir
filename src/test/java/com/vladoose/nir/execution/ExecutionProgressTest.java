package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.PrivateRequestListItemResponse;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/** Этапы заявки с исполнением (спека 2026-10-19-deal-execution §3): шаги, «Дальше», код этапа в виде и в списке. */
@SpringBootTest
@Transactional
class ExecutionProgressTest {

    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestViewBuilder views;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealExecutionRepository executions;
    @Autowired DealPaymentRepository payments;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Tender accepted(String total) {
        Tender t = requests.createEmpty();
        Distributor d = distributors.save(Distributor.builder().name("ZZ Этапы " + System.nanoTime()).build());
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, total, 481), d.getId(), "100", "1"));
        return t;
    }

    private PrivateRequestViewResponse view(Tender t) {
        em.flush();
        em.clear();
        return views.build(requests.findById(t.getId()));
    }

    private static String states(DealProgress p) {
        return p.steps().stream().map(s -> s.key() + ":" + s.state()).collect(joining(" "));
    }

    @Test
    void acceptedOffer_executionStepsNextAndListChip() {
        Tender t = accepted("3070455");
        PrivateRequestViewResponse v = view(t);
        assertThat(states(v.getProgress())).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE "
                + "CONTRACT:CURRENT PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO RESULT:TODO");
        assertThat(v.getProgress().next()).isEqualTo(new Next("Клиент согласился на КП № 481 — договор или счёт", "EXEC_CONTRACT", null));
        assertThat(v.getStage()).isEqualTo("EXEC_CONTRACT");
        assertThat(v.getStageText()).isEqualTo("Договор");

        PrivateRequestListItemResponse row = views.list(List.of(requests.findById(t.getId()))).get(0);
        assertThat(row.getStage()).isEqualTo("EXEC_CONTRACT");
        assertThat(row.getStageText()).isEqualTo("Договор");
    }

    @Test
    void allDone_resultCurrent_closeWon() {
        Tender t = accepted("1000");
        executions.save(DealExecution.builder().tenderId(t.getId()).contractKind(ContractKind.NONE).fromStock(true)
                .deliveredOn(LocalDate.of(2026, 10, 19)).docsOn(LocalDate.of(2026, 10, 19)).build());
        payments.save(DealPayment.builder().tenderId(t.getId()).amount(new BigDecimal("1000")).paidOn(LocalDate.of(2026, 10, 19)).build());
        PrivateRequestViewResponse v = view(t);
        assertThat(states(v.getProgress())).endsWith("CONTRACT:SKIPPED PAYMENT:DONE SUPPLIERS:SKIPPED DELIVERY:DONE DOCS:DONE RESULT:CURRENT");
        assertThat(v.getProgress().next()).isEqualTo(new Next("Всё сделано — закройте сделку", "CLOSE_WON", null));
        assertThat(v.getStage()).isEqualTo("EXEC_DONE");
    }

    @Test
    void closedWonWithoutData_executionStepsSkipped() {
        Tender t = accepted("1000");
        requests.close(t.getId(), PrivateRequestStatus.WON);
        PrivateRequestViewResponse v = view(t);
        assertThat(states(v.getProgress())).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE "
                + "CONTRACT:SKIPPED PAYMENT:SKIPPED SUPPLIERS:SKIPPED DELIVERY:SKIPPED DOCS:SKIPPED RESULT:DONE");
        assertThat(v.getProgress().steps().subList(4, 9)).allMatch(s -> s.detail() == null);
        assertThat(v.getStage()).isEqualTo("WON");
    }

    @Test
    void offerRevertedAfterExecution_stepsByDataNextAboutOffer() {
        Tender t = accepted("1000");
        executions.save(DealExecution.builder().tenderId(t.getId()).contractNumber("42").build());
        em.flush();
        ClientOffer o = offers.findByTenderIdOrderByIdDesc(t.getId()).get(0);
        o.setStatus(ClientOfferStatus.SENT);
        o.setSentAt(OffsetDateTime.now());
        PrivateRequestViewResponse v = view(t);
        assertThat(v.getStage()).isEqualTo("KP_SENT");
        assertThat(v.getProgress().next().action()).isEqualTo("OFFER_DECISION");
        assertThat(states(v.getProgress())).contains("OFFER:CURRENT CONTRACT:DONE PAYMENT:TODO");
    }
}
