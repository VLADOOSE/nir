package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.ExecutionMark;
import com.vladoose.nir.dto.request.SupplierField;
import com.vladoose.nir.dto.request.SupplierMark;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.DealEventRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.execution.DealExecutionService.PaymentInput;
import com.vladoose.nir.service.execution.DealExecutionService.Upload;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Строки исполнения в ленте (спека 2026-10-19-deal-execution §10): тексты, «только дата», автор, ключ блока. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class ExecutionTimelineTest {

    @Autowired DealExecutionService service;
    @Autowired DealTimelineService timeline;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealEventRepository dealEvents;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String nb(String s) { return s.replace(' ', ' '); }

    private List<DealTimelineItem> execItems(Tender t) {
        em.flush();
        em.clear();
        return timeline.forPrivateRequest(t.getId(), null, 50).items().stream()
                .filter(i -> i.kind().startsWith("EXEC_")).toList();
    }

    @Test
    void executionLines() {
        Tender t = requests.createEmpty();
        Distributor fotek = distributors.save(Distributor.builder().name("ФОТЕК ZZ " + System.nanoTime()).build());
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "3070455", 481), fotek.getId(), "1662150", "1"));
        Long id = t.getId(), f = fotek.getId();
        service.updateField(id, ExecutionField.CONTRACT_NUMBER, "42/2026");
        service.updateField(id, ExecutionField.CONTRACT_DATE, "2026-10-10");
        service.addPayment(id, new PaymentInput(null, new BigDecimal("1535227.50"), LocalDate.of(2026, 10, 11), "предоплата"));
        service.setSupplierMark(id, f, SupplierMark.ORDER, "2026-10-12", "заказ 7");
        service.updateSupplier(id, f, SupplierField.CURRENCY, "RUB");
        service.updateSupplier(id, f, SupplierField.AMOUNT, "277025");
        service.addPayment(id, new PaymentInput(f, new BigDecimal("277025"), LocalDate.of(2026, 10, 13), null));
        service.setSupplierMark(id, f, SupplierMark.SHIP, "2026-10-14", null);
        service.setMark(id, ExecutionMark.DELIVERY, "2026-10-15", "обучение");
        service.setMark(id, ExecutionMark.DOCS, "2026-10-15", null);
        service.attach(id, new Upload("Договор 42.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8)),
                null, DealFileStage.CONTRACT, null, null, "vlad");

        List<DealTimelineItem> items = execItems(t);
        String name = fotek.getName();
        assertThat(items).extracting(DealTimelineItem::kind, DealTimelineItem::text).containsExactly(
                tuple("EXEC_CONTRACT", "Договор № 42/2026 от 10.10 подписан"),
                tuple("EXEC_PAY_CLIENT", "Оплата клиента: " + nb("1 535 227,50 тг") + " — предоплата"),
                tuple("EXEC_ORDER", "Заказ у " + name + " — заказ 7"),
                tuple("EXEC_PAY_SUPPLIER", "Оплата " + name + ": " + nb("277 025 руб.")),
                tuple("EXEC_SHIPPED", name + " отгрузил"),
                tuple("EXEC_DELIVERED", "Поставлено клиенту — обучение"),
                tuple("EXEC_DOCS", "Закрывающие документы подписаны"),
                tuple("EXEC_FILE", "«Договор 42.pdf» — к договору"));
        assertThat(items).extracting(DealTimelineItem::linkType).containsExactly("EXEC_CONTRACT", "EXEC_PAYMENT",
                "EXEC_SUPPLIERS", "EXEC_SUPPLIERS", "EXEC_SUPPLIERS", "EXEC_DELIVERY", "EXEC_DOCS", "EXEC_CONTRACT");
        assertThat(items.get(1).dateOnly()).isTrue();
        assertThat(items.get(1).author()).isEqualTo("vlad");
        assertThat(items.get(0).author()).isNull();
        assertThat(items.get(7).dateOnly()).isFalse();
        assertThat(items.get(7).author()).isEqualTo("vlad");
        assertThat(items).allMatch(i -> i.linkId() == null);
    }

    /** Всё в один день (полдень по часам рынка) — строки в порядке процесса, а не таблиц: договор → оплата → заказ → отгрузка → поставка. */
    @Test
    void sameDay_processOrder() {
        Tender t = requests.createEmpty();
        Distributor fotek = distributors.save(Distributor.builder().name("ФОТЕК ZZ " + System.nanoTime()).build());
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "1000", 1), fotek.getId(), "100", "1"));
        Long id = t.getId(), f = fotek.getId();
        String day = "2026-10-10";
        service.setMark(id, ExecutionMark.DOCS, day, null);
        service.setMark(id, ExecutionMark.DELIVERY, day, null);
        service.setSupplierMark(id, f, SupplierMark.SHIP, day, null);
        service.addPayment(id, new PaymentInput(f, new BigDecimal("100"), LocalDate.of(2026, 10, 10), null));
        service.setSupplierMark(id, f, SupplierMark.ORDER, day, null);
        service.addPayment(id, new PaymentInput(null, new BigDecimal("1000"), LocalDate.of(2026, 10, 10), null));
        service.updateField(id, ExecutionField.CONTRACT_DATE, day);
        assertThat(execItems(t)).extracting(DealTimelineItem::kind).containsExactly(
                "EXEC_CONTRACT", "EXEC_PAY_CLIENT", "EXEC_ORDER", "EXEC_PAY_SUPPLIER", "EXEC_SHIPPED", "EXEC_DELIVERED", "EXEC_DOCS");
    }

    /**
     * Факт, датированный днём принятия КП, — не выше строки «клиент согласился»: на полудне счёт, выставленный в тот же
     * день после принятия в 16:20, вставал бы раньше самого согласия. Другие дни — полдень, как было.
     */
    @Test
    void sameDayAsAcceptance_belowAcceptance() {
        Tender t = requests.createEmpty();
        ClientOffer o = offers.save(offer(t, ClientOfferStatus.ACCEPTED, "1000", 1));
        OffsetDateTime acceptedAt = LocalDateTime.of(2026, 10, 10, 16, 20).atZone(Market.KZ.zone()).toOffsetDateTime();
        dealEvents.save(DealEvent.builder().tenderId(t.getId()).type(DealEventType.STAGE).code(DealEventService.OFFER_STATUS)
                .text("КП № 1: клиент согласился").refId(o.getId()).occurredAt(acceptedAt).build());
        service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "INVOICE");
        service.updateField(t.getId(), ExecutionField.CONTRACT_DATE, "2026-10-10");
        service.addPayment(t.getId(), new PaymentInput(null, new BigDecimal("1000"), LocalDate.of(2026, 10, 12), null));
        service.addPayment(t.getId(), new PaymentInput(null, new BigDecimal("1"), LocalDate.of(2026, 10, 9), null));   // день раньше — свой полдень
        em.flush();
        em.clear();
        List<DealTimelineItem> items = timeline.forPrivateRequest(t.getId(), null, 50).items().stream()
                .filter(i -> i.kind().startsWith("EXEC_") || "STAGE".equals(i.kind())).toList();
        assertThat(items).extracting(DealTimelineItem::kind).containsExactly("EXEC_PAY_CLIENT", "STAGE", "EXEC_CONTRACT", "EXEC_PAY_CLIENT");
        assertThat(items.get(0).at()).isEqualTo(LocalDate.of(2026, 10, 9).atTime(12, 0).atZone(Market.KZ.zone()).toOffsetDateTime());
        assertThat(items.get(2).at().atZoneSameInstant(Market.KZ.zone()).toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(items.get(3).at()).isEqualTo(LocalDate.of(2026, 10, 12).atTime(12, 0).atZone(Market.KZ.zone()).toOffsetDateTime());
    }

    @Test
    void invoiceAndWithoutContract() {
        Tender t = requests.createEmpty();
        offers.save(offer(t, ClientOfferStatus.ACCEPTED, "100", 1));
        service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "INVOICE");
        service.updateField(t.getId(), ExecutionField.CONTRACT_NUMBER, "15");
        service.updateField(t.getId(), ExecutionField.CONTRACT_DATE, "2026-10-10");
        assertThat(execItems(t)).extracting(DealTimelineItem::text).containsExactly("Счёт № 15 от 10.10 выставлен");

        service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "NONE");   // «без договора» — строки нет
        assertThat(execItems(t)).isEmpty();
    }
}
