package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.controller.ClientOfferController;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestPricesService;
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

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** 💡 и «нет цены» в таблице цен; 💡 у закупки строки КП и в «Поставщик ▾»; «Сверить» во «Входящих» — данные. */
@SpringBootTest
@Transactional
class SupplierReplyMarksTest {

    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestPricesService prices;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired ClientOfferRequestService fromRequest;
    @Autowired ClientOfferController offers;      // GET /api/client-offers/{id}: ответ со строками (метод без @PreAuthorize)
    @Autowired SupplierReplyService replies;
    @Autowired EntityManager em;
    Tender t;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("ZZ Кушетка", null, 1, null, null, null),
                input("ZZ Столик", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Метки " + System.nanoTime()).build());
        pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .sentAt(OffsetDateTime.now()).responseDate(LocalDate.now()).build());
        pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(0)).requestedQuantity(1)
                .responsePrice(new BigDecimal("95000")).responseAuto(true).build()));
        pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(1)).requestedQuantity(1).build()));
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void pricesTable_autoMark_noPriceState_replyLink() {
        PrivateRequestPricesResponse p = prices.build(t.getId());
        assertThat(p.lines().get(0).cells().get(0).auto()).isTrue();
        assertThat(p.lines().get(1).cells().get(0).state()).isEqualTo("NO_PRICE");
        assertThat(p.suppliers().get(0).replyRequestId()).isEqualTo(pr.getId());
    }

    @Test
    void offerLine_purchaseUnverified_untilVerify_andSupplierOffersAuto() {
        ClientOffer o = fromRequest.createFromPrivateRequest(t.getId(), "admin");
        var line = offers.get(o.getId()).getItems().stream().filter(i -> i.getPriceRequestItemId() != null).findFirst().orElseThrow();
        assertThat(line.getPurchaseUnverified()).isTrue();
        assertThat(line.getReplyRequestId()).isEqualTo(pr.getId());
        assertThat(fromRequest.offers(o.getId(), line.getId())).singleElement()
                .satisfies(s -> {
                    assertThat(s.auto()).isTrue();
                    assertThat(s.priceRequestId()).isEqualTo(pr.getId());
                });

        replies.verify(pr.getId());
        em.clear();
        var after = offers.get(o.getId()).getItems().stream().filter(i -> i.getPriceRequestItemId() != null).findFirst().orElseThrow();
        assertThat(after.getPurchaseUnverified()).isFalse();
    }

    @Autowired InboundEmailRepository inbound;
    @Autowired com.vladoose.nir.controller.InboundController inboundController;

    @Test
    void inboundRowListsReplyFiles() {
        InboundEmail e = inbound.save(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("s@x.kz").subject("Re")
                .receivedAt(OffsetDateTime.now()).type(InboundType.SUPPLIER_RESPONSE).matchedPriceRequestId(pr.getId())
                .status(InboundStatus.NEW).build());
        var o = replies.ingest(pr, List.of(ReplyFixtures.parsed("КП.xlsx", ReplyFixtures.table("KZT", "ZZ Столик", "48 500"))), null);
        replies.linkInbound(o.fileIds(), e.getId());
        em.flush();
        assertThat(inboundController.list(null)).filteredOn(r -> r.getId().equals(e.getId()))
                .singleElement().extracting(r -> r.getFiles()).isEqualTo(List.of("КП.xlsx"));
    }
}
