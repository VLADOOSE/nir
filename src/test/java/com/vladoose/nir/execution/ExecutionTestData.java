package com.vladoose.nir.execution;

import com.vladoose.nir.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** КП сделки для тестов исполнения: статус, итог, строки «поставщик — закупка × кол-во». Рынок ставит листенер. */
final class ExecutionTestData {

    private ExecutionTestData() {}

    static ClientOffer offer(Tender t, ClientOfferStatus status, String total, int number) {
        return ClientOffer.builder()
                .number(number)
                .offerDate(LocalDate.of(2026, 10, 19))
                .status(status)
                .title("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ")
                .vatEnabled(true)
                .defaultMarkupPct(new BigDecimal("20"))
                .rounding(OfferRounding.NONE)
                .tableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null))))
                .detailsInName(true)
                .terms(new ArrayList<>())
                .termsStyle(TermsStyle.LIST)
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoff(OfferSignoff.DIRECTOR)
                .tenderId(t.getId())
                .totalAmount(total == null ? null : new BigDecimal(total))
                .build();
    }

    /** Строка-позиция КП; purchase null — без закупки, distributorId null — без поставщика. */
    static ClientOffer line(ClientOffer o, Long distributorId, String purchase, String qty) {
        int no = o.getItems().size() + 1;
        o.getItems().add(ClientOfferItem.builder().offer(o).lineNo(no).name("Позиция " + no)
                .quantity(new BigDecimal(qty)).purchasePrice(purchase == null ? null : new BigDecimal(purchase))
                .distributorId(distributorId).build());
        return o;
    }
}
