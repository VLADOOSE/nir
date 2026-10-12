package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.NewPricesResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Плашка новых цен (спека 2026-10-12 §8; решение плана 8). */
@SpringBootTest
@Transactional
class ClientOfferNewPricesTest {

    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired ClientOfferRepository offers;

    Tender t;
    List<TenderLot> lots;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Новые 1", null, 1, null, null, null),
                input("ZZ Новые 2", null, 1, null, null, null), input("ZZ Новые 3", null, 1, null, null, null)),
                LinesMode.APPEND);
        lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name) {
        return distributors.save(Distributor.builder().name(name + " " + System.nanoTime()).build());
    }

    private PriceRequestItem answer(Distributor d, TenderLot lot, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private List<String> names(NewPricesResponse r) {
        return r.lines().stream().map(NewPricesResponse.Line::name).toList();
    }

    @Test
    void bannerCountsEmptyAndChangedOnly_deliberateChoiceUntouched() {
        Distributor a = dist("ZZ Новые А"), b = dist("ZZ Новые Б"), c = dist("ZZ Новые В");
        PriceRequestItem aOn1 = answer(a, lots.get(0), "100");
        PriceRequestItem aOn3 = answer(a, lots.get(2), "300");
        em.flush();
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        // строка 3 — закупка вписана руками, без ссылки (ручная правка на экране снимает ссылку)
        ClientOfferItem third = kp.getItems().get(2);
        third.setPurchasePrice(new BigDecimal("250.00"));
        third.setPriceRequestItemId(null);
        third.setDistributorId(null);
        em.flush();
        assertThat(requestOffers.newPrices(kp.getId()).lines()).isEmpty();

        answer(b, lots.get(1), "50");          // строка 2 без закупки — теперь есть цена
        answer(c, lots.get(0), "80");          // по строке 1 дешевле — но выбранный ответ не менялся: не трогаем
        aOn3.setResponsePrice(new BigDecimal("310"));   // строка 3 ручная — не трогаем
        em.flush();
        assertThat(names(requestOffers.newPrices(kp.getId()))).containsExactly("ZZ Новые 2");

        aOn1.setResponsePrice(new BigDecimal("110"));   // цена выбранного ответа изменилась
        em.flush();
        NewPricesResponse banner = requestOffers.newPrices(kp.getId());
        assertThat(names(banner)).containsExactly("ZZ Новые 1", "ZZ Новые 2");
        assertThat(banner.lines().get(0).newPrice()).isEqualByComparingTo("110");

        int version = kp.getVersion();
        ClientOffer applied = requestOffers.applyNewPrices(kp.getId());
        em.flush();
        assertThat(applied.getItems().get(0).getPurchasePrice()).isEqualByComparingTo("110");
        assertThat(applied.getItems().get(0).getPriceRequestItemId()).isEqualTo(aOn1.getId());
        assertThat(applied.getItems().get(1).getPurchasePrice()).isEqualByComparingTo("50");
        assertThat(applied.getItems().get(1).getDistributorId()).isEqualTo(b.getId());
        assertThat(applied.getItems().get(2).getPurchasePrice()).isEqualByComparingTo("250");
        assertThat(applied.getVersion()).isGreaterThan(version);
        assertThat(requestOffers.newPrices(kp.getId()).lines()).isEmpty();
    }

    @Test
    void onlyDraftFromRequest() {
        Distributor a = dist("ZZ Новые Черновик");
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        answer(a, lots.get(0), "100");
        em.flush();
        assertThat(requestOffers.newPrices(kp.getId()).lines()).hasSize(1);
        kp.setStatus(ClientOfferStatus.SENT);
        em.flush();
        assertThat(requestOffers.newPrices(kp.getId()).lines()).isEmpty();
        assertThatThrownBy(() -> requestOffers.applyNewPrices(kp.getId())).isInstanceOf(BadRequestException.class);
    }

    /** Позицию удалили из заявки после КП: строка остаётся, плашка её не считает (Review Focus 3). */
    @Test
    void positionDeletedAfterKpKeepsRowAndIsIgnored() {
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
        service.deleteLine(t.getId(), lots.get(1).getId());
        em.flush();
        em.clear();   // ON DELETE SET NULL делает база — перечитать КП
        answer(dist("ZZ Новые После"), em.find(TenderLot.class, lots.get(0).getId()), "70");
        em.flush();
        ClientOffer back = offers.findById(kp.getId()).orElseThrow();
        assertThat(back.getItems()).hasSize(3);
        assertThat(back.getItems().get(1).getTenderLotId()).isNull();
        assertThat(back.getItems().get(1).getName()).isEqualTo("ZZ Новые 2");
        assertThat(names(requestOffers.newPrices(kp.getId()))).containsExactly("ZZ Новые 1");
    }
}
