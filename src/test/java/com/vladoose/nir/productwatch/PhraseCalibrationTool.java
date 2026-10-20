package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

/**
 * Перебор настроек правила фраз на наборе golden.tsv (задача 6 плана): ложных ★ и найденных COPIED по каждой точке.
 * Не тест: запуск — PRODUCT_SPEC_CALIBRATION=1 ./gradlew test --tests '*PhraseCalibrationTool*' -i | grep CAL
 */
@EnabledIfEnvironmentVariable(named = "PRODUCT_SPEC_CALIBRATION", matches = "1")
class PhraseCalibrationTool {

    @Test
    void sweep() throws Exception {
        List<ProductGroup> groups = ProductFixtures.seedGroups();
        List<SiteProduct> catalog = ProductFixtures.catalogFull();
        List<ProductFixtures.GoldenRow> rows = ProductFixtures.golden();
        Map<String, String> specs = new HashMap<>();
        for (ProductFixtures.GoldenRow r : rows) specs.put(r.file(), ProductFixtures.spec(r.file()));
        List<String> out = new ArrayList<>();
        for (boolean desc : new boolean[] {true, false})
            for (int minDesc : new int[] {2, 3, 4})
                for (int maxW : new int[] {4})
                    for (double rare : new double[] {0.05})
                        for (int copyMin : new int[] {1, 2, 3, 4})
                            for (boolean fam : new boolean[] {true, false})
                            for (double cover : new double[] {0, 0.02, 0.03, 0.04, 0.06}) {
                                if (!desc && minDesc != 2) continue;
                                CatalogPhrases.Rules rules = new CatalogPhrases.Rules(desc, 2, minDesc, maxW, rare, copyMin, fam, cover);
                                CatalogSnapshot snap = CatalogSnapshot.build(groups, catalog, rules);
                                int falseStar = 0, found = 0, copiedFalse = 0;
                                List<String> bad = new ArrayList<>();
                                for (ProductFixtures.GoldenRow row : rows) {
                                    List<LotMatch> m = ProductMatcher.match(snap, row.announcement(),
                                            List.of(new LotInput(1, row.lot(), row.description(), specs.get(row.file()))));
                                    ProductMatchLevel got = m.stream().map(LotMatch::level).reduce(null, ProductMatchLevel::max);
                                    String product = m.stream().filter(x -> x.level() == ProductMatchLevel.COPIED && x.productId() != null)
                                            .map(x -> snap.products().stream().filter(p -> p.id() == x.productId()).findFirst()
                                                    .map(CatalogProduct::name).orElse("?")).findFirst().orElse("");
                                    boolean exactExpected = row.expected().equals("COPIED") || row.expected().equals("NAMED");
                                    if (got != null && got.exact() && !exactExpected) { falseStar++; bad.add(row.file().substring(0, 10) + "=" + got); }
                                    if (got == ProductMatchLevel.COPIED && !row.expected().equals("COPIED")) copiedFalse++;
                                    if (row.expected().equals("COPIED") && got == ProductMatchLevel.COPIED && product.startsWith(row.productPrefix())) found++;
                                }
                                out.add(String.format("CAL\tdesc=%s minDesc=%d maxW=%d rare=%.2f copyMin=%d fam=%s cover=%.2f\tложных★=%d\tложныхCOPIED=%d\tCOPIED найдено=%d\t%s",
                                        desc, minDesc, maxW, rare, copyMin, fam, cover, falseStar, copiedFalse, found, String.join(",", bad)));
                            }
        out.sort(Comparator.comparing((String x) -> Integer.parseInt(x.replaceAll(".*ложных★=(\\d+).*", "$1")))
                .thenComparing(x -> -Integer.parseInt(x.replaceAll(".*COPIED найдено=(\\d+).*", "$1"))));
        out.forEach(System.out::println);
    }
}
