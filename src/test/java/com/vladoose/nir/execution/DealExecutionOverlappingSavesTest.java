package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.SupplierField;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Две вкладки правят разные поля исполнения — наложившиеся сохранения не затирают друг друга (@DynamicUpdate у
 * DealExecution и DealSupplier). Без общей транзакции теста: перекрытие — вложенной REQUIRES_NEW, уборка — в @AfterEach.
 */
@SpringBootTest
class DealExecutionOverlappingSavesTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DealExecutionRepository executions;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired TenderRepository tenders;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private TransactionTemplate txNew;
    private Long id;
    private Long distributorId;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tx = new TransactionTemplate(txManager);
        txNew = new TransactionTemplate(txManager);
        txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        id = tx.execute(s -> {
            Tender t = requests.createEmpty();
            Distributor d = distributors.save(Distributor.builder().name("ZZ Наложение " + System.nanoTime()).build());
            distributorId = d.getId();
            offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "1000", 1), d.getId(), "100", "1"));
            service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "CONTRACT");
            service.updateSupplier(t.getId(), d.getId(), SupplierField.AMOUNT, "90");
            return t.getId();
        });
    }

    @AfterEach
    void tearDown() {
        tx.execute(s -> {
            offers.deleteAll(offers.findByTenderIdOrderByIdDesc(id));
            tenders.deleteById(id);                 // deal_* — каскадом
            distributors.deleteById(distributorId);
            return null;
        });
        MarketContext.clear();
    }

    @Test
    void overlappingExecutionFieldSavesKeepBothFields() {
        tx.execute(s -> {
            DealExecution first = executions.findById(id).orElseThrow();      // первый PATCH загрузил строку
            first.setContractNumber("42/2026");
            txNew.execute(s2 -> service.updateField(id, ExecutionField.PREPAY_PCT, "50"));   // второй — раньше
            return null;                                                       // первый коммитится последним
        });
        DealExecution back = tx.execute(s -> executions.findById(id).orElseThrow());
        assertThat(back.getContractNumber()).isEqualTo("42/2026");
        assertThat(back.getPrepayPct()).isEqualByComparingTo("50");
    }

    @Test
    void overlappingSupplierSavesKeepBothFields() {
        tx.execute(s -> {
            DealSupplier first = suppliers.findByTenderIdAndDistributorId(id, distributorId).orElseThrow();
            first.setOrderedOn(LocalDate.now(Market.KZ.zone()));
            first.setOrderNote("№ 7");
            txNew.execute(s2 -> service.updateSupplier(id, distributorId, SupplierField.AMOUNT, "95"));
            return null;
        });
        DealSupplier back = tx.execute(s -> suppliers.findByTenderIdAndDistributorId(id, distributorId).orElseThrow());
        assertThat(back.getOrderNote()).isEqualTo("№ 7");
        assertThat(back.getAmount()).isEqualByComparingTo("95");
    }
}
