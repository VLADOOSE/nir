package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.SupplierField;
import com.vladoose.nir.dto.request.SupplierMark;
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

/** Поставщики сделки (спека 2026-10-19-deal-execution §4.3, §6.2, §7). */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class DealExecutionSuppliersTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DealPaymentRepository payments;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.now(Market.KZ.zone());

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(Market m) {
        return distributors.save(Distributor.builder().name("ZZ Поставщик " + System.nanoTime()).market(m).build());
    }

    private Tender accepted(Distributor... ds) {
        Tender t = requests.createEmpty();
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "1000", 1);
        for (Distributor d : ds) line(o, d.getId(), "100", "1");
        offers.save(o);
        return t;
    }

    private DealSupplier row(Tender t, Distributor d) {
        em.flush();
        em.clear();
        return suppliers.findByTenderIdAndDistributorId(t.getId(), d.getId()).orElseThrow();
    }

    @Test
    void addManual_inOfferOrTwice400_otherMarket404() {
        Distributor fotek = dist(Market.KZ), rm = dist(Market.KZ), rf = dist(Market.RF);
        Tender t = accepted(fotek);
        service.addSupplier(t.getId(), rm.getId(), new BigDecimal("277025"), "rub");
        DealSupplier s = row(t, rm);
        assertThat(s.isManual()).isTrue();
        assertThat(s.getCurrency()).isEqualTo("RUB");
        assertThat(s.getAmount()).isEqualByComparingTo("277025");

        assertThatThrownBy(() -> service.addSupplier(t.getId(), rm.getId(), null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("«" + rm.getName() + "» уже есть в сделке");
        assertThatThrownBy(() -> service.addSupplier(t.getId(), fotek.getId(), null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("«" + fotek.getName() + "» уже есть в сделке");
        assertThatThrownBy(() -> service.addSupplier(t.getId(), rf.getId(), null, null))
                .isInstanceOf(NotFoundException.class).hasMessage("Поставщик не найден");
    }

    @Test
    void currencyChangeWithPayments_400() {
        Distributor fotek = dist(Market.KZ);
        Tender t = accepted(fotek);
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.CURRENCY, "RUB");     // заводит строку, платежей нет
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.AMOUNT, "277 025");
        service.addPayment(t.getId(), new PaymentInput(fotek.getId(), new BigDecimal("100000"), DAY, null));
        assertThatThrownBy(() -> service.updateSupplier(t.getId(), fotek.getId(), SupplierField.CURRENCY, "KZT"))
                .isInstanceOf(BadRequestException.class).hasMessage("Сначала удалите платежи поставщику — они в прежней валюте");
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.CURRENCY, "RUB");     // та же валюта — можно
        assertThat(row(t, fotek).getCurrency()).isEqualTo("RUB");
    }

    @Test
    void excludeWithPayments400_excludedCannotBePaid() {
        Distributor a = dist(Market.KZ), b = dist(Market.KZ);
        Tender t = accepted(a, b);
        service.addPayment(t.getId(), new PaymentInput(a.getId(), BigDecimal.TEN, DAY, null));
        assertThatThrownBy(() -> service.updateSupplier(t.getId(), a.getId(), SupplierField.EXCLUDED, "true"))
                .isInstanceOf(BadRequestException.class).hasMessage("У поставщика есть платежи — сначала удалите их");

        service.updateSupplier(t.getId(), b.getId(), SupplierField.EXCLUDED, "true");
        assertThat(row(t, b).isExcluded()).isTrue();
        assertThatThrownBy(() -> service.addPayment(t.getId(), new PaymentInput(b.getId(), BigDecimal.TEN, DAY, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Поставщик отмечен «Не заказываем» — платёж ему не вносится");
    }

    @Test
    void fromStockOnlyWithoutSupplierMarks() {
        Distributor a = dist(Market.KZ);
        Tender t = accepted(a);
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.ORDER, DAY.toString(), "№ 7");
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.FROM_STOCK, "true"))
                .isInstanceOf(BadRequestException.class).hasMessage("Сначала уберите отметки, платежи и файлы поставщиков");
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.ORDER, null, null);
        service.updateField(t.getId(), ExecutionField.FROM_STOCK, "true");   // отметок больше нет
    }

    @Test
    void marks_orderAndShip() {
        Distributor a = dist(Market.KZ);
        Tender t = accepted(a);
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.ORDER, DAY.toString(), " № 7 ");
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.SHIP, DAY.toString(), "трек 123");
        DealSupplier s = row(t, a);
        assertThat(s.getOrderedOn()).isEqualTo(DAY);
        assertThat(s.getOrderNote()).isEqualTo("№ 7");
        assertThat(s.getShippedOn()).isEqualTo(DAY);
        assertThat(s.getShipNote()).isEqualTo("трек 123");
        assertThatThrownBy(() -> service.setSupplierMark(t.getId(), a.getId(), SupplierMark.SHIP, DAY.plusDays(1).toString(), null))
                .isInstanceOf(BadRequestException.class).hasMessage("Дата отгрузки: дата ещё не наступила — отмечаются только факты");
    }

    @Test
    void deleteOnlyManual_cascadesPayments() {
        Distributor fotek = dist(Market.KZ), rm = dist(Market.KZ);
        Tender t = accepted(fotek);
        service.addSupplier(t.getId(), rm.getId(), new BigDecimal("500"), null);
        service.addPayment(t.getId(), new PaymentInput(rm.getId(), new BigDecimal("500"), DAY, null));
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.AMOUNT, "90");
        assertThatThrownBy(() -> service.deleteSupplier(t.getId(), fotek.getId()))
                .isInstanceOf(BadRequestException.class).hasMessage("Поставщик из КП — отметьте «Не заказываем»");
        service.deleteSupplier(t.getId(), rm.getId());
        em.flush();
        em.clear();
        assertThat(suppliers.findByTenderIdAndDistributorId(t.getId(), rm.getId())).isEmpty();
        assertThat(payments.findByTenderIdIn(List.of(t.getId()))).isEmpty();
        assertThatThrownBy(() -> service.deleteSupplier(t.getId(), rm.getId()))
                .isInstanceOf(NotFoundException.class).hasMessage("Поставщика нет в сделке");
    }
}
