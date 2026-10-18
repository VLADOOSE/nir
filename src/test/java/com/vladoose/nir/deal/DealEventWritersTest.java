package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ActivityApplyService;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.WinnerAssignmentService;
import com.vladoose.nir.util.DocFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Смена этапа пишет событие там, где действие уже происходит (спека 2026-10-18 §5). */
@SpringBootTest
@Transactional
class DealEventWritersTest {

    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferRequestService offersFromRequest;
    @Autowired ClientOfferService offers;
    @Autowired DealEventRepository events;
    @Autowired ActivityApplyService applies;
    @Autowired TenderRepository tenders;
    @Autowired WinnerAssignmentService winners;
    @Autowired MedEquipmentRepository equipment;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private List<DealEvent> stages(Long tenderId) {
        return events.findByTenderIdOrderByOccurredAtAscIdAsc(tenderId).stream()
                .filter(e -> e.getType() == DealEventType.STAGE).toList();
    }

    private Tender requestWithLine() {
        Tender t = requests.createEmpty();
        return requests.addLines(t.getId(), List.of(input("ZZ Сделка позиция", null, 1, null, null, null)), LinesMode.APPEND);
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void offerStatusWritesEventExceptDraftToSent() {
        Tender t = requestWithLine();
        ClientOffer o = offersFromRequest.createFromPrivateRequest(t.getId(), "vlad");
        offers.setStatus(o.getId(), ClientOfferStatus.SENT);
        assertThat(stages(t.getId())).isEmpty();
        offers.setStatus(o.getId(), ClientOfferStatus.ACCEPTED);
        offers.setStatus(o.getId(), ClientOfferStatus.ACCEPTED);   // тот же статус — без события
        offers.setStatus(o.getId(), ClientOfferStatus.REJECTED);
        assertThat(stages(t.getId())).extracting(DealEvent::getCode, DealEvent::getText).containsExactly(
                tuple("OFFER_STATUS", "КП № " + o.getNumber() + ": клиент согласился"),
                tuple("OFFER_STATUS", "КП № " + o.getNumber() + ": клиент отказался"));
        assertThat(stages(t.getId())).allMatch(e -> o.getId().equals(e.getRefId()) && "vlad".equals(e.getAuthor()));
    }

    @Test
    void closeAndReopenWriteEventsOnlyOnChange() {
        Tender t = requestWithLine();
        requests.close(t.getId(), PrivateRequestStatus.WON);
        requests.close(t.getId(), PrivateRequestStatus.WON);
        requests.reopen(t.getId());
        requests.reopen(t.getId());
        assertThat(stages(t.getId())).extracting(DealEvent::getCode, DealEvent::getText).containsExactly(
                tuple("CLOSED", "Закрыта: купили у нас"), tuple("REOPENED", "Возвращена в работу"));
    }

    @Test
    void applyStatusChangeWritesEvent() {
        Tender t = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE").build());
        ActivityApply a = applies.save(ActivityApply.builder().tender(t).status("DRAFT").build());
        a.setStatus("SUBMITTED");
        applies.saveWithStatusEvent(a, "DRAFT");
        applies.saveWithStatusEvent(a, "SUBMITTED");
        assertThat(stages(t.getId())).extracting(DealEvent::getCode, DealEvent::getText, DealEvent::getRefId)
                .containsExactly(tuple("APPLY_STATUS", "Заявка на участие № " + a.getId() + ": подана", a.getId()));
    }

    @Test
    void winnerAssignmentWritesEvent() {
        Tender t = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE").build());
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(1).equipName("ZZ Облучатель").quantity(2).build());
        t = tenders.save(t);
        TenderLot lot = t.getLots().get(0);
        MedEquipment me = equipment.save(MedEquipment.builder().name("ZZ Обор " + System.nanoTime()).manufact("M").build());
        Distributor d = distributors.save(Distributor.builder().name("ZZ Дистр " + System.nanoTime()).build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status("RESPONDED").build();
        pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).medEquipment(me)
                .requestedQuantity(1).responsePrice(new BigDecimal("850000")).build());
        pr = priceRequests.save(pr);

        winners.assignWinner(t.getId(), lot.getId(), pr.getId(), null);

        assertThat(stages(t.getId())).extracting(DealEvent::getCode, DealEvent::getText, DealEvent::getRefId)
                .containsExactly(tuple("WINNER", "Победитель по лоту «ZZ Облучатель»: " + d.getName() + ", "
                        + DocFormat.money(new BigDecimal("850000.00")), lot.getId()));
    }
}
