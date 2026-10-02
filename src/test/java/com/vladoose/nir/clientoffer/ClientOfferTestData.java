package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Готовые КП и строки для тестов (без БД — рынок ставит листенер при сохранении или тест явно). */
final class ClientOfferTestData {

    private ClientOfferTestData() {}

    static ClientOffer newOffer(int number) {
        return ClientOffer.builder()
                .number(number)
                .offerDate(LocalDate.of(2026, 9, 14))
                .status(ClientOfferStatus.DRAFT)
                .title("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ")
                .vatEnabled(true)
                .defaultMarkupPct(new BigDecimal("20"))
                .rounding(OfferRounding.NONE)
                .tableColumns(new ArrayList<>(List.of(new OfferColumn("NUM", null), new OfferColumn("NAME", null),
                        new OfferColumn("SUM", null))))
                .detailsInName(true)
                .terms(new ArrayList<>(List.of(new OfferTerm("Порядок оплаты", "100% предоплата"))))
                .termsStyle(TermsStyle.LIST)
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoff(OfferSignoff.DIRECTOR)
                .build();
    }

    /** Позиция: количество 1, закупка «как у продажи». vat == null — без НДС. */
    static ClientOfferItem item(ClientOffer offer, int lineNo, String name, String purchase, String vat) {
        return ClientOfferItem.builder()
                .offer(offer)
                .lineNo(lineNo)
                .name(name)
                .quantity(BigDecimal.ONE)
                .purchasePrice(purchase == null ? null : new BigDecimal(purchase))
                .vatRate(vat == null ? null : new BigDecimal(vat))
                .build();
    }
}
