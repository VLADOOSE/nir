package com.vladoose.nir.clientoffer;

import com.vladoose.nir.service.offer.PurchaseOffer;
import com.vladoose.nir.service.offer.PurchaseOffers;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Цена закупки по умолчанию (спека 2026-10-12 §8, решение плана 2). */
class PurchaseOffersTest {

    private static PurchaseOffer o(long item, long request, long distributor, String price, String status) {
        return new PurchaseOffer(item, request, 7L, distributor, "Поставщик " + distributor, new BigDecimal(price), status, null);
    }

    @Test
    void acceptedBeatsCheaperResponded() {
        assertThat(PurchaseOffers.best(PurchaseOffers.current(List.of(
                o(1, 10, 1, "100", "RESPONDED"), o(2, 11, 2, "120", "ACCEPTED"))))).get()
                .extracting(PurchaseOffer::priceRequestItemId).isEqualTo(2L);
    }

    @Test
    void declinedRejectedClosedAreNotCandidates() {
        assertThat(PurchaseOffers.current(List.of(o(1, 10, 1, "50", "DECLINED"), o(2, 11, 2, "60", "REJECTED"),
                o(3, 12, 3, "70", "CLOSED"), o(4, 13, 4, "80", "SENT"))))
                .extracting(PurchaseOffer::priceRequestItemId).containsExactly(4L);
    }

    @Test
    void latestPricedLinePerSupplier_olderPriceIsNotOffered() {
        assertThat(PurchaseOffers.current(List.of(o(1, 10, 1, "90", "RESPONDED"), o(2, 15, 1, "130", "RESPONDED"))))
                .extracting(PurchaseOffer::priceRequestItemId).containsExactly(2L);
    }

    @Test
    void tieGoesToEarlierRequest_listIsCheapestFirst() {
        List<PurchaseOffer> current = PurchaseOffers.current(List.of(
                o(5, 20, 1, "100", "RESPONDED"), o(6, 18, 2, "100", "RESPONDED"), o(7, 19, 3, "99", "RESPONDED")));
        assertThat(current).extracting(PurchaseOffer::priceRequestItemId).containsExactly(7L, 6L, 5L);
        assertThat(PurchaseOffers.best(current.subList(1, 3))).get().extracting(PurchaseOffer::priceRequestItemId).isEqualTo(6L);
    }

    @Test
    void nothingGivesNothing() {
        assertThat(PurchaseOffers.best(List.of())).isEmpty();
    }
}
