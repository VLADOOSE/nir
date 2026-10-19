package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Таблицы исполнения сделки (V34, спека 2026-10-19-deal-execution §5): каскады, ограничения, проекция файлов. */
@SpringBootTest
@Transactional
class DealExecutionSchemaTest {

    @Autowired PrivateRequestService requests;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired DealExecutionRepository executions;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DealPaymentRepository payments;
    @Autowired DealFileRepository files;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor distributor() {
        return distributors.save(Distributor.builder().name("ZZ Исполнение " + System.nanoTime()).build());
    }

    private DealSupplier supplier(Tender t, Long distributorId) {
        return suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(distributorId).currency("KZT").build());
    }

    private DealPayment payment(Tender t, Long supplierRow, String amount) {
        return payments.save(DealPayment.builder().tenderId(t.getId()).dealSupplierId(supplierRow)
                .amount(new BigDecimal(amount)).paidOn(DAY).build());
    }

    private DealFile file(Tender t, DealFileStage stage, Long paymentId, Long supplierRow) {
        byte[] b = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        return files.save(DealFile.builder().tenderId(t.getId()).stage(stage).paymentId(paymentId).dealSupplierId(supplierRow)
                .fileName("Договор 42.pdf").mimeType("application/pdf").sizeBytes(b.length).sha256("ab").content(b).build());
    }

    private long count(String table, Long tenderId) {
        return jdbc.queryForObject("select count(*) from " + table + " where tender_id = ?", Long.class, tenderId);
    }

    @Test
    void rowsLiveWithDealAndGoWithIt() {
        Tender t = requests.createEmpty();
        executions.save(DealExecution.builder().tenderId(t.getId()).contractKind(ContractKind.CONTRACT).contractNumber("42").build());
        DealSupplier s = supplier(t, distributor().getId());
        DealPayment p = payment(t, null, "100.00");
        payment(t, s.getId(), "50.00");
        file(t, DealFileStage.CLIENT_PAYMENT, p.getId(), null);
        em.flush();
        em.clear();

        DealExecution back = executions.findById(t.getId()).orElseThrow();
        assertThat(back.getPrepayPct()).isEqualByComparingTo("100");
        assertThat(back.getUpdatedAt()).isNotNull();
        List<DealFileMeta> meta = files.findMetaByTenderId(t.getId());
        assertThat(meta).singleElement().satisfies(m -> {
            assertThat(m.stage()).isEqualTo(DealFileStage.CLIENT_PAYMENT);
            assertThat(m.paymentId()).isEqualTo(p.getId());
            assertThat(m.name()).isEqualTo("Договор 42.pdf");
            assertThat(m.size()).isEqualTo(8);
        });

        jdbc.update("delete from tender where id = ?", t.getId());
        for (String table : List.of("deal_execution", "deal_supplier", "deal_payment", "deal_file")) {
            assertThat(count(table, t.getId())).as(table).isZero();
        }
    }

    @Test
    void deletingSupplierTakesItsPaymentsAndFiles() {
        Tender t = requests.createEmpty();
        DealSupplier s = supplier(t, distributor().getId());
        DealPayment p = payment(t, s.getId(), "50.00");
        file(t, DealFileStage.SUPPLIER, p.getId(), s.getId());
        em.flush();
        jdbc.update("delete from deal_supplier where id = ?", s.getId());
        assertThat(count("deal_payment", t.getId())).isZero();
        assertThat(count("deal_file", t.getId())).isZero();
    }

    @Test
    void distributorWithDealSupplierCannotBeDeleted() {
        Tender t = requests.createEmpty();
        Distributor d = distributor();
        supplier(t, d.getId());
        em.flush();
        assertThatThrownBy(() -> jdbc.update("delete from distributor where id = ?", d.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void paymentAmountMustBePositive() {
        Tender t = requests.createEmpty();
        assertThatThrownBy(() -> payments.saveAndFlush(DealPayment.builder().tenderId(t.getId())
                .amount(BigDecimal.ZERO).paidOn(DAY).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneRowPerSupplierInDeal() {
        Tender t = requests.createEmpty();
        Long d = distributor().getId();
        suppliers.saveAndFlush(DealSupplier.builder().tenderId(t.getId()).distributorId(d).currency("KZT").build());
        assertThatThrownBy(() -> suppliers.saveAndFlush(DealSupplier.builder().tenderId(t.getId()).distributorId(d)
                .currency("KZT").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
