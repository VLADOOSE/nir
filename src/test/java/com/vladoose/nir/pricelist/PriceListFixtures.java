package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceListRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Поставщик и прайс для тестов блока (имена — «ZZ …» с меткой времени: название поставщика уникально по всем рынкам). */
public final class PriceListFixtures {

    private PriceListFixtures() {}

    public static Distributor supplier(DistributorRepository repo, Market market, String name) {
        return repo.saveAndFlush(Distributor.builder().name("ZZ " + name + " " + System.nanoTime()).market(market)
                .email("zz-" + System.nanoTime() + "@example.kz").build());
    }

    /** Прайс со строками «наименование, цена, ключ модели»; наша колонка — 0. */
    public static PriceList list(PriceListRepository repo, Distributor d, PriceListStatus status, String currency,
                                 java.time.LocalDate listDate, Object[]... rows) {
        PriceList p = PriceList.builder().market(d.getMarket()).distributor(d).status(status).currency(currency)
                .listDate(listDate).columns(new ArrayList<>(List.of(new PriceListColumn(0, "Цена"))))
                .ourColumn(0).fileName("zz-прайс.xlsx").sha256(Long.toHexString(System.nanoTime()))
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).data(new byte[]{1}).build();
        int n = 0;
        for (Object[] r : rows) {
            BigDecimal price = r[1] == null ? null : new BigDecimal(r[1].toString());
            p.getItems().add(PriceListItem.builder().priceList(p).lineNo(++n).name((String) r[0])
                    .prices(new ArrayList<>(Arrays.asList(price))).ourPrice(price)
                    .modelKey(r.length > 2 ? (String) r[2] : null).build());
        }
        return repo.saveAndFlush(p);
    }
}
